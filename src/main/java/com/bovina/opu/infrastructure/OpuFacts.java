package com.bovina.opu.infrastructure;

import com.bovina.animals.application.DonorDirectory.Donor;
import com.bovina.opu.domain.OocyteCollection;
import com.bovina.parties.application.FarmOrigin;
import com.bovina.platform.application.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OpuFacts {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public OpuFacts(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Set<UUID> donors(UUID tenant, UUID session) {
    return new HashSet<>(
        jdbc.query(
            "SELECT donor_id FROM oocyte_collection WHERE organization_id=? AND opu_session_id=?",
            (rs, n) -> rs.getObject(1, UUID.class),
            tenant,
            session));
  }

  public void insert(UUID tenant, List<OocyteCollection> collections) {
    jdbc.batchUpdate(
        """
      INSERT INTO oocyte_collection(id,organization_id,opu_session_id,donor_id,collected_at,total_recovered,viable,
        follicles_aspirated,notes,status,version,origin_type,source_document_id,import_batch_id,api_client_id,recorded_by,recorded_at)
      VALUES (?,?,?,?,?,?,?,?,?,'RECORDED',0,?,?,?,?,?,?)
      """,
        collections,
        100,
        (ps, c) -> {
          var r = c.registration();
          var p = c.provenance();
          ps.setObject(1, c.id());
          ps.setObject(2, tenant);
          ps.setObject(3, c.sessionId());
          ps.setObject(4, c.donorId());
          ps.setTimestamp(5, Timestamp.from(r.collectedAt()));
          ps.setInt(6, r.counts().totalRecovered());
          ps.setInt(7, r.counts().viable());
          ps.setObject(8, r.counts().folliclesAspirated());
          ps.setString(9, r.notes());
          ps.setString(10, p.originType().name());
          ps.setObject(11, p.sourceDocumentId());
          ps.setObject(12, p.importBatchId());
          ps.setObject(13, p.apiClientId());
          ps.setObject(14, p.recordedByUserId());
          ps.setTimestamp(15, Timestamp.from(p.recordedAt()));
        });
  }

  public void registerImport(
      ExecutionContext c, UUID batch, UUID document, String hash, Instant now) {
    jdbc.update(
        "INSERT INTO import_batch(id,organization_id,kind,mode,request_hash,source_document_id,recorded_by,recorded_at) VALUES (?,?,'OPU_COLLECTIONS','ATOMIC',?,?,?,?)",
        batch,
        c.tenantId(),
        hash,
        document,
        c.actorId(),
        Timestamp.from(now));
  }

  public void freezeFarm(UUID tenant, UUID session, FarmOrigin origin) {
    jdbc.update(
        "INSERT INTO opu_farm_snapshot(organization_id,session_id,snapshot) VALUES (?,?,?::jsonb)",
        tenant,
        session,
        json.writeValueAsString(origin));
  }

  public FarmOrigin farmSnapshot(UUID tenant, UUID session) {
    return jdbc
        .query(
            "SELECT snapshot FROM opu_farm_snapshot WHERE organization_id=? AND session_id=?",
            (rs, n) -> json.readValue(rs.getString(1), FarmOrigin.class),
            tenant,
            session)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Donor donorSnapshot(UUID tenant, UUID collection) {
    return jdbc
        .query(
            "SELECT snapshot FROM oocyte_donor_snapshot WHERE organization_id=? AND collection_id=?",
            (rs, n) -> json.readValue(rs.getString(1), Donor.class),
            tenant,
            collection)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void freezeDonors(
      UUID tenant, List<OocyteCollection> collections, Map<UUID, Donor> donors) {
    jdbc.batchUpdate(
        "INSERT INTO oocyte_donor_snapshot(organization_id,collection_id,snapshot) VALUES (?,?,?::jsonb)",
        collections,
        100,
        (ps, c) -> {
          ps.setObject(1, tenant);
          ps.setObject(2, c.id());
          ps.setString(3, json.writeValueAsString(Objects.requireNonNull(donors.get(c.donorId()))));
        });
  }

  public Summary summary(UUID tenant, UUID session) {
    return jdbc.queryForObject(
        "SELECT count(*),coalesce(sum(total_recovered),0),coalesce(sum(viable),0) FROM oocyte_collection WHERE organization_id=? AND opu_session_id=?",
        (rs, n) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
        tenant,
        session);
  }

  public record Summary(long collections, long totalRecovered, long viable) {}
}
