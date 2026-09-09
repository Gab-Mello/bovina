package com.bovina.parties.infrastructure;

import com.bovina.parties.application.ClientView;
import com.bovina.parties.application.CreateClient;
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
public class ClientCommandStore {
  private final JdbcTemplate jdbc;
  private final JsonMapper mapper;

  public ClientCommandStore(JdbcTemplate jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public String requestHash(ExecutionContext context, CreateClient command) {
    // Versioned typed serialization, not raw JSON: field order/whitespace in HTTP cannot change
    // intent.
    var canonical = new HashInput("CREATE_CLIENT_V1", context.actorId(), command);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public boolean claim(
      UUID id, ExecutionContext context, CreateClient command, String hash, Instant now) {
    return jdbc.update(
            """
        INSERT INTO idempotent_command(id, organization_id, command_type, idempotency_key, actor_id, request_hash, status, created_at)
        VALUES (?, ?, 'CREATE_CLIENT_V1', ?, ?, ?, 'PROCESSING', ?)
        ON CONFLICT (organization_id, command_type, idempotency_key) DO NOTHING
        """,
            id,
            context.tenantId(),
            command.metadata().commandId(),
            context.actorId(),
            hash,
            Timestamp.from(now))
        == 1;
  }

  public ClientView replay(ExecutionContext context, UUID key, String hash) {
    var stored =
        jdbc.queryForObject(
            """
        SELECT request_hash, status, response_json::text FROM idempotent_command
        WHERE organization_id=? AND command_type='CREATE_CLIENT_V1' AND idempotency_key=?
        """,
            (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3)),
            context.tenantId(),
            key);
    if (stored == null || !stored.hash().equals(hash))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "The idempotency key belongs to a different command");
    if (!stored.status().equals("COMPLETED"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "COMMAND_IN_PROGRESS",
          "Retry the command with the same idempotency key");
    return mapper.readValue(stored.response(), ClientView.class);
  }

  public void attachClientRole(UUID tenant, UUID client) {
    jdbc.update(
        "INSERT INTO party_role(organization_id,party_id,role) VALUES (?,?,'CLIENT')",
        tenant,
        client);
  }

  public boolean isClient(UUID tenant, UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM party_role WHERE organization_id=? AND party_id=? AND role='CLIENT')",
            Boolean.class,
            tenant,
            id));
  }

  public void complete(ExecutionContext context, UUID key, ClientView result, Instant now) {
    int updated =
        jdbc.update(
            """
        UPDATE idempotent_command SET status='COMPLETED', completed_at=?, response_status=201,
            response_json=CAST(? AS jsonb), resource_id=?
        WHERE organization_id=? AND command_type='CREATE_CLIENT_V1' AND idempotency_key=? AND status='PROCESSING'
        """,
            Timestamp.from(now),
            mapper.writeValueAsString(result),
            result.id(),
            context.tenantId(),
            key);
    if (updated != 1) throw new IllegalStateException("Idempotency claim was lost");
  }

  private record HashInput(String schema, UUID actorId, CreateClient command) {}

  private record Stored(String hash, String status, String response) {}
}
