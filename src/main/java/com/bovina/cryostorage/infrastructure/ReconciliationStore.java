package com.bovina.cryostorage.infrastructure;

import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.SearchPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationStore {
  private static final String DIFFERENCES_SQL =
      """
      SELECT coalesce(e.package_id,o.package_id),e.expected_location_id,o.observed_location_id,
             e.expected_sequence,o.raw_identifier
      FROM reconciliation_expected e
      FULL JOIN reconciliation_observation o
        ON o.organization_id=e.organization_id
       AND o.reconciliation_id=e.reconciliation_id AND o.package_id=e.package_id
      WHERE coalesce(e.organization_id,o.organization_id)=?
        AND coalesce(e.reconciliation_id,o.reconciliation_id)=?
        AND (e.package_id IS NULL OR o.id IS NULL
             OR e.expected_location_id IS DISTINCT FROM o.observed_location_id)
      """;
  private final JdbcTemplate jdbc;

  public ReconciliationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void open(ExecutionContext c, Session session, Instant now) {
    jdbc.update(
        "INSERT INTO inventory_reconciliation(id,organization_id,establishment_id,scope_location_id,method,status,snapshot_at,started_at,started_by,notes) VALUES (?,?,?,?,?,'OPEN',?,?,?,?)",
        session.id(),
        c.tenantId(),
        session.establishmentId(),
        session.scopeLocationId(),
        session.method(),
        Timestamp.from(now),
        Timestamp.from(now),
        c.actorId(),
        session.notes());
    jdbc.update(
        "INSERT INTO reconciliation_expected(organization_id,reconciliation_id,package_id,expected_location_id,expected_sequence) SELECT organization_id,?,id,current_location_id,last_movement_sequence FROM embryo_package WHERE organization_id=? AND establishment_id=? AND status='SEALED' AND current_location_id IS NOT NULL AND (CAST(? AS uuid) IS NULL OR current_location_id=?)",
        session.id(),
        c.tenantId(),
        session.establishmentId(),
        session.scopeLocationId(),
        session.scopeLocationId());
  }

  public Session find(UUID tenant, UUID id, boolean lock) {
    var sql =
        "SELECT id,establishment_id,scope_location_id,method,status,version,notes FROM inventory_reconciliation WHERE organization_id=? AND id=?"
            + (lock ? " FOR UPDATE" : "");
    var rows =
        jdbc.query(
            sql,
            (rs, row) ->
                new Session(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getLong(6),
                    rs.getString(7)),
            tenant,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void observe(
      ExecutionContext c, UUID reconciliationId, List<Observation> observations, Instant now) {
    var result =
        jdbc.batchUpdate(
            "INSERT INTO reconciliation_observation(id,organization_id,reconciliation_id,package_id,raw_identifier,observed_location_id,observed_at,observed_by,notes) VALUES (?,?,?,?,?,?,?,?,?)",
            observations,
            100,
            (statement, o) -> {
              statement.setObject(1, o.id());
              statement.setObject(2, c.tenantId());
              statement.setObject(3, reconciliationId);
              statement.setObject(4, o.packageId());
              statement.setString(5, o.rawIdentifier());
              statement.setObject(6, o.observedLocationId());
              statement.setTimestamp(7, Timestamp.from(o.observedAt()));
              statement.setObject(8, c.actorId());
              statement.setString(9, o.notes());
            });
    for (var chunk : result)
      for (var count : chunk)
        if (count != 1) throw new IllegalStateException("Reconciliation observation insert failed");
  }

  public void close(ExecutionContext c, UUID id, long expectedVersion, Instant now) {
    if (jdbc.update(
            "UPDATE inventory_reconciliation SET status='CLOSED',closed_at=?,closed_by=?,version=version+1 WHERE organization_id=? AND id=? AND status='OPEN' AND version=?",
            Timestamp.from(now),
            c.actorId(),
            c.tenantId(),
            id,
            expectedVersion)
        != 1) throw new IllegalStateException("Reconciliation changed while lock was held");
  }

  public List<Discrepancy> differences(UUID tenant, UUID id, SearchPage page) {
    return jdbc.query(
        DIFFERENCES_SQL
            + " ORDER BY coalesce(e.package_id,o.package_id),o.raw_identifier,o.id LIMIT ? OFFSET ?",
        (rs, row) ->
            new Discrepancy(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4) == null ? null : rs.getLong(4),
                rs.getString(5)),
        tenant,
        id,
        page.size(),
        page.offset());
  }

  public boolean hasDifference(UUID tenant, UUID id, UUID packageId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (" + DIFFERENCES_SQL + " AND coalesce(e.package_id,o.package_id)=?)",
            Boolean.class,
            tenant,
            id,
            packageId));
  }

  public record Session(
      UUID id,
      UUID establishmentId,
      UUID scopeLocationId,
      String method,
      String status,
      long version,
      String notes) {}

  public record Observation(
      UUID id,
      UUID packageId,
      String rawIdentifier,
      UUID observedLocationId,
      Instant observedAt,
      String notes) {}

  public record Discrepancy(
      UUID packageId,
      UUID expectedLocationId,
      UUID observedLocationId,
      Long expectedSequence,
      String rawIdentifier) {}
}
