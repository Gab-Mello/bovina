package com.bovina.platform.infrastructure;

import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class CommandReceiptStore {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public CommandReceiptStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public String hash(UUID actor, String operation, Object input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json.writeValueAsBytes(new Intent(actor, operation, input))));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public boolean claim(
      UUID id, ExecutionContext context, UUID key, String operation, String hash, Instant now) {
    return jdbc.update(
            """
        INSERT INTO idempotent_command(id,organization_id,command_type,idempotency_key,
            actor_id,request_hash,status,created_at)
        VALUES (?,?,?,?,?,?,'PROCESSING',?)
        ON CONFLICT (organization_id,command_type,idempotency_key) DO NOTHING
        """,
            id,
            context.tenantId(),
            operation,
            key,
            context.actorId(),
            hash,
            Timestamp.from(now))
        == 1;
  }

  public <T> T replay(UUID tenant, UUID key, String operation, String hash, Class<T> type) {
    // A separate READ COMMITTED statement observes the winner after ON CONFLICT waits.
    var saved =
        jdbc.queryForObject(
            """
        SELECT request_hash,status,response_json::text FROM idempotent_command
        WHERE organization_id=? AND command_type=? AND idempotency_key=?
        """,
            (rs, n) -> new Saved(rs.getString(1), rs.getString(2), rs.getString(3)),
            tenant,
            operation,
            key);
    if (saved == null || !hash.equals(saved.hash()))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "The idempotency key belongs to a different command");
    if (!saved.status().equals("COMPLETED"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "COMMAND_IN_PROGRESS",
          "Retry the command with the same idempotency key");
    return json.readValue(saved.response(), type);
  }

  public void complete(UUID tenant, UUID key, String operation, Object result, Instant now) {
    if (jdbc.update(
            """
        UPDATE idempotent_command SET status='COMPLETED',completed_at=?,response_status=200,
            response_json=CAST(? AS jsonb)
        WHERE organization_id=? AND command_type=? AND idempotency_key=? AND status='PROCESSING'
        """,
            Timestamp.from(now),
            json.writeValueAsString(result),
            tenant,
            operation,
            key)
        != 1) throw new IllegalStateException("Idempotency claim was lost");
  }

  private record Intent(UUID actor, String operation, Object input) {}

  private record Saved(String hash, String status, String response) {}
}
