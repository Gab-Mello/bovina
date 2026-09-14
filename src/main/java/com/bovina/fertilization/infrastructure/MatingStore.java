package com.bovina.fertilization.infrastructure;

import com.bovina.fertilization.application.MatingLineageSnapshot;
import com.bovina.fertilization.domain.Mating;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.domain.DataProvenance;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MatingStore {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public MatingStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static final RowMapper<Mating> ROW =
      (rs, n) ->
          new Mating(
              rs.getObject("id", UUID.class),
              rs.getObject("oocyte_collection_id", UUID.class),
              rs.getObject("semen_batch_id", UUID.class),
              rs.getInt("allocated_oocytes"),
              rs.getTimestamp("fertilized_at").toInstant(),
              rs.getString("method"),
              rs.getObject("responsible_professional_id", UUID.class),
              Mating.Status.valueOf(rs.getString("status")),
              rs.getLong("version"),
              new DataProvenance(
                  DataProvenance.Origin.valueOf(rs.getString("origin_type")),
                  rs.getObject("source_document_id", UUID.class),
                  rs.getObject("import_batch_id", UUID.class),
                  rs.getObject("api_client_id", UUID.class),
                  rs.getObject("recorded_by", UUID.class),
                  rs.getTimestamp("recorded_at").toInstant(),
                  null,
                  null,
                  null));

  public long allocated(UUID tenant, UUID collection) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(allocated_oocytes),0) FROM mating WHERE organization_id=? AND oocyte_collection_id=? AND status<>'CANCELLED'",
        Long.class,
        tenant,
        collection);
  }

  public void insertAll(
      UUID tenant, List<Mating> matings, Map<UUID, MatingLineageSnapshot> snapshots) {
    jdbc.batchUpdate(
        """
        INSERT INTO mating(id,organization_id,oocyte_collection_id,semen_batch_id,allocated_oocytes,
          fertilized_at,method,responsible_professional_id,status,version,origin_type,source_document_id,
          import_batch_id,api_client_id,recorded_by,recorded_at)
        VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)
        """,
        matings,
        100,
        (ps, m) -> {
          var p = m.provenance();
          ps.setObject(1, m.id());
          ps.setObject(2, tenant);
          ps.setObject(3, m.collectionId());
          ps.setObject(4, m.semenBatchId());
          ps.setInt(5, m.allocatedOocytes());
          ps.setTimestamp(6, Timestamp.from(m.fertilizedAt()));
          ps.setString(7, m.method());
          ps.setObject(8, m.responsibleProfessionalId());
          ps.setString(9, m.status().name());
          ps.setString(10, p.originType().name());
          ps.setObject(11, p.sourceDocumentId());
          ps.setObject(12, p.importBatchId());
          ps.setObject(13, p.apiClientId());
          ps.setObject(14, p.recordedByUserId());
          ps.setTimestamp(15, Timestamp.from(p.recordedAt()));
        });
    jdbc.batchUpdate(
        "INSERT INTO mating_lineage_snapshot(organization_id,mating_id,snapshot) VALUES (?,?,?::jsonb)",
        matings,
        100,
        (ps, m) -> {
          ps.setObject(1, tenant);
          ps.setObject(2, m.id());
          ps.setString(3, json.writeValueAsString(snapshots.get(m.id())));
        });
  }

  public Optional<Mating> find(UUID tenant, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM mating WHERE organization_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            ROW,
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public List<Mating> page(UUID tenant, UUID collection, UUID semenBatch, int size, int offset) {
    var sql = new StringBuilder("SELECT * FROM mating WHERE organization_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (collection != null) {
      sql.append(" AND oocyte_collection_id=?");
      args.add(collection);
    }
    if (semenBatch != null) {
      sql.append(" AND semen_batch_id=?");
      args.add(semenBatch);
    }
    sql.append(" ORDER BY fertilized_at DESC,id LIMIT ? OFFSET ?");
    args.add(size);
    args.add(offset);
    return jdbc.query(sql.toString(), ROW, args.toArray());
  }

  public MatingLineageSnapshot snapshot(UUID tenant, UUID mating) {
    return jdbc
        .query(
            "SELECT snapshot FROM mating_lineage_snapshot WHERE organization_id=? AND mating_id=?",
            (rs, n) -> json.readValue(rs.getString(1), MatingLineageSnapshot.class),
            tenant,
            mating)
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public void registerImport(
      ExecutionContext c, UUID batch, UUID document, String hash, Instant now) {
    jdbc.update(
        "INSERT INTO import_batch(id,organization_id,kind,mode,request_hash,source_document_id,recorded_by,recorded_at) VALUES (?,?,'MATINGS','ATOMIC',?,?,?,?)",
        batch,
        c.tenantId(),
        hash,
        document,
        c.actorId(),
        Timestamp.from(now));
  }

  public void requestCorrection(
      UUID tenant, UUID id, UUID mating, String reason, Object proposed, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO record_correction(id,organization_id,subject_type,subject_id,reason,proposed_change,status,requested_by,requested_at) VALUES (?,?,'MATING',?,?,?::jsonb,'REQUESTED',?,?)",
        id,
        tenant,
        mating,
        reason,
        json.writeValueAsString(proposed),
        actor,
        Timestamp.from(now));
  }

  public void complete(UUID tenant, UUID mating, long expectedVersion) {
    var changed =
        jdbc.update(
            "UPDATE mating SET status='COMPLETED',version=version+1 WHERE organization_id=? AND id=? AND status='FERTILIZED' AND version=?",
            tenant,
            mating,
            expectedVersion);
    if (changed != 1)
      throw new com.bovina.platform.application.ApplicationFailure(
          com.bovina.platform.application.ApplicationFailure.Kind.CONFLICT,
          "MATING_COMPLETION_CONFLICT",
          "Mating changed or is already completed");
  }
}
