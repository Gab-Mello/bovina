package com.bovina.parties.infrastructure;

import com.bovina.parties.domain.*;
import com.bovina.parties.domain.ClientImportBatch.*;
import com.bovina.platform.application.*;
import com.bovina.platform.infrastructure.CommandReceiptStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClientImportStore {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final CommandReceiptStore fingerprints;
  private static final RowMapper<ItemResult> RESULT =
      (rs, n) ->
          new ItemResult(
              rs.getObject("item_id", UUID.class),
              rs.getObject("client_id", UUID.class),
              ItemStatus.valueOf(rs.getString("status")),
              rs.getString("error_code"));

  public ClientImportStore(
      JdbcTemplate jdbc, NamedParameterJdbcTemplate named, CommandReceiptStore fingerprints) {
    this.jdbc = jdbc;
    this.named = named;
    this.fingerprints = fingerprints;
  }

  public String fingerprint(ExecutionContext context, ClientImportBatch batch) {
    return fingerprints.hash(context.actorId(), "IMPORT_CLIENT_BATCH_V1", batch);
  }

  public boolean bind(ExecutionContext c, ClientImportBatch b, String hash, Instant now) {
    return jdbc.update(
            """
        INSERT INTO import_batch(id,organization_id,kind,mode,request_hash,source_document_id,recorded_by,recorded_at)
        VALUES (?,?,'CLIENT_MASTER_DATA',?,?,?,?,?) ON CONFLICT (id) DO NOTHING
        """,
            b.batchId(),
            c.tenantId(),
            b.mode().name(),
            hash,
            b.sourceDocumentId(),
            c.actorId(),
            Timestamp.from(now))
        == 1;
  }

  public Optional<String> storedHash(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT request_hash FROM import_batch WHERE organization_id=? AND id=?",
            (rs, row) -> rs.getString(1),
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public void lock(UUID tenant, UUID id) {
    jdbc.queryForObject(
        "SELECT id FROM import_batch WHERE organization_id=? AND id=? FOR UPDATE",
        UUID.class,
        tenant,
        id);
  }

  public Set<UUID> existingClients(UUID tenant, ClientImportBatch batch) {
    var ids = batch.items().stream().map(Row::id).filter(Objects::nonNull).toList();
    if (ids.isEmpty()) return Set.of();
    return new HashSet<>(
        named.query(
            "SELECT id FROM party WHERE organization_id=:tenant AND id IN (:ids)",
            Map.of("tenant", tenant, "ids", ids),
            (rs, n) -> rs.getObject(1, UUID.class)));
  }

  public List<ItemResult> results(UUID tenant, UUID batch) {
    return jdbc.query(
        "SELECT * FROM import_item_result WHERE organization_id=? AND batch_id=?",
        RESULT,
        tenant,
        batch);
  }

  public Optional<ItemResult> result(UUID tenant, UUID batch, UUID item) {
    return jdbc
        .query(
            "SELECT * FROM import_item_result WHERE organization_id=? AND batch_id=? AND item_id=?",
            RESULT,
            tenant,
            batch,
            item)
        .stream()
        .findFirst();
  }

  public void recordResults(UUID tenant, UUID batch, List<ItemResult> results) {
    jdbc.batchUpdate(
        "INSERT INTO import_item_result(organization_id,batch_id,item_id,client_id,status,error_code) VALUES (?,?,?,?,?,?)",
        results.stream()
            .map(
                r ->
                    new Object[] {
                      tenant, batch, r.itemId(), r.clientId(), r.status().name(), r.errorCode()
                    })
            .toList());
  }

  public void insertClients(List<Party> clients) {
    jdbc.batchUpdate(
        """
        INSERT INTO party(id,organization_id,type,display_name,status,version,occurred_at,origin_type,
            source_document_id,import_batch_id,recorded_by,recorded_at) VALUES (?,?,?,?,'ACTIVE',0,?,'IMPORT',?,?,?,?)
        """,
        clients.stream()
            .map(
                p ->
                    new Object[] {
                      p.id(),
                      p.organizationId(),
                      p.type().name(),
                      p.displayName(),
                      Timestamp.from(p.occurredAt()),
                      p.provenance().sourceDocumentId(),
                      p.provenance().importBatchId(),
                      p.provenance().recordedByUserId(),
                      Timestamp.from(p.provenance().recordedAt())
                    })
            .toList());
    jdbc.batchUpdate(
        "INSERT INTO party_role(organization_id,party_id,role) VALUES (?,?,'CLIENT')",
        clients.stream().map(p -> new Object[] {p.organizationId(), p.id()}).toList());
  }
}
