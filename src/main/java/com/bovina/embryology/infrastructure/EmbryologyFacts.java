package com.bovina.embryology.infrastructure;

import com.bovina.embryology.domain.*;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.domain.DataProvenance;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class EmbryologyFacts {
  private final JdbcTemplate jdbc;

  public EmbryologyFacts(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<EmbryoEvaluation> EVALUATION =
      (rs, n) ->
          new EmbryoEvaluation(
              rs.getObject("id", UUID.class),
              rs.getObject("embryo_id", UUID.class),
              rs.getObject("scheme_version_id", UUID.class),
              rs.getObject("development_stage_code_id", UUID.class),
              rs.getObject("quality_grade_code_id", UUID.class),
              rs.getTimestamp("evaluated_at").toInstant(),
              rs.getObject("evaluator_professional_id", UUID.class),
              rs.getString("notes"),
              rs.getObject("supersedes_evaluation_id", UUID.class),
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

  public void registerImport(
      ExecutionContext c, UUID batch, String kind, UUID document, String hash, Instant now) {
    jdbc.update(
        "INSERT INTO import_batch(id,organization_id,kind,mode,request_hash,source_document_id,recorded_by,recorded_at) VALUES (?,? ,?,'ATOMIC',?,?,?,?)",
        batch,
        c.tenantId(),
        kind,
        hash,
        document,
        c.actorId(),
        Timestamp.from(now));
  }

  public boolean hasActiveHold(UUID tenant, UUID embryo) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM embryo_hold WHERE organization_id=? AND embryo_id=? AND released_at IS NULL)",
            Boolean.class,
            tenant,
            embryo));
  }

  public Set<UUID> embryosWithActiveHold(UUID tenant, Collection<UUID> embryos) {
    if (embryos.isEmpty()) return Set.of();
    var placeholders = String.join(",", Collections.nCopies(embryos.size(), "?"));
    var parameters = new ArrayList<Object>(embryos.size() + 1);
    parameters.add(tenant);
    parameters.addAll(embryos);
    return Set.copyOf(
        jdbc.query(
            "SELECT DISTINCT embryo_id FROM embryo_hold WHERE organization_id=? AND released_at IS NULL AND embryo_id IN ("
                + placeholders
                + ")",
            (rs, row) -> rs.getObject(1, UUID.class),
            parameters.toArray()));
  }

  public Hold openHold(
      UUID tenant, UUID id, UUID embryo, String type, String reason, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO embryo_hold(id,organization_id,embryo_id,hold_type,reason,opened_by,opened_at) VALUES (?,?,?,?,?,?,?)",
        id,
        tenant,
        embryo,
        type,
        reason,
        actor,
        Timestamp.from(now));
    return new Hold(id, embryo, type, reason, actor, now, null, null, null);
  }

  public Hold releaseHold(UUID tenant, UUID id, String reason, UUID actor, Instant now) {
    var changed =
        jdbc.update(
            "UPDATE embryo_hold SET released_by=?,released_at=?,release_reason=? WHERE organization_id=? AND id=? AND released_at IS NULL",
            actor,
            Timestamp.from(now),
            reason,
            tenant,
            id);
    if (changed != 1) throw missing("EMBRYO_HOLD_NOT_FOUND");
    return hold(tenant, id);
  }

  public List<Hold> holds(UUID tenant, UUID embryo) {
    return jdbc.query(
        "SELECT * FROM embryo_hold WHERE organization_id=? AND embryo_id=? ORDER BY opened_at,id",
        HOLD,
        tenant,
        embryo);
  }

  private Hold hold(UUID tenant, UUID id) {
    return jdbc
        .query("SELECT * FROM embryo_hold WHERE organization_id=? AND id=?", HOLD, tenant, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing("EMBRYO_HOLD_NOT_FOUND"));
  }

  private static final RowMapper<Hold> HOLD =
      (rs, n) ->
          new Hold(
              rs.getObject("id", UUID.class),
              rs.getObject("embryo_id", UUID.class),
              rs.getString("hold_type"),
              rs.getString("reason"),
              rs.getObject("opened_by", UUID.class),
              rs.getTimestamp("opened_at").toInstant(),
              rs.getObject("released_by", UUID.class),
              instant(rs, "released_at"),
              rs.getString("release_reason"));

  public void insertEvaluations(UUID tenant, List<EmbryoEvaluation> evaluations) {
    jdbc.batchUpdate(
        """
      INSERT INTO embryo_evaluation(id,organization_id,embryo_id,scheme_version_id,development_stage_code_id,
        quality_grade_code_id,evaluated_at,evaluator_professional_id,notes,supersedes_evaluation_id,
        origin_type,source_document_id,import_batch_id,api_client_id,recorded_by,recorded_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      """,
        evaluations,
        200,
        (ps, e) -> {
          var p = e.provenance();
          ps.setObject(1, e.id());
          ps.setObject(2, tenant);
          ps.setObject(3, e.embryoId());
          ps.setObject(4, e.schemeVersionId());
          ps.setObject(5, e.developmentStageCodeId());
          ps.setObject(6, e.qualityGradeCodeId());
          ps.setTimestamp(7, Timestamp.from(e.evaluatedAt()));
          ps.setObject(8, e.evaluatorProfessionalId());
          ps.setString(9, e.notes());
          ps.setObject(10, e.supersedesEvaluationId());
          ps.setString(11, p.originType().name());
          ps.setObject(12, p.sourceDocumentId());
          ps.setObject(13, p.importBatchId());
          ps.setObject(14, p.apiClientId());
          ps.setObject(15, p.recordedByUserId());
          ps.setTimestamp(16, Timestamp.from(p.recordedAt()));
        });
    jdbc.batchUpdate(
        """
      INSERT INTO embryo_current_assessment(organization_id,embryo_id,evaluation_id) VALUES (?,?,?)
      ON CONFLICT (organization_id,embryo_id) DO UPDATE SET evaluation_id=excluded.evaluation_id
      """,
        evaluations,
        200,
        (ps, e) -> {
          ps.setObject(1, tenant);
          ps.setObject(2, e.embryoId());
          ps.setObject(3, e.id());
        });
  }

  public EmbryoEvaluation currentEvaluation(UUID tenant, UUID embryo) {
    return jdbc
        .query(
            "SELECT e.* FROM embryo_current_assessment c JOIN embryo_evaluation e ON e.organization_id=c.organization_id AND e.id=c.evaluation_id WHERE c.organization_id=? AND c.embryo_id=?",
            EVALUATION,
            tenant,
            embryo)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<EmbryoEvaluation> history(UUID tenant, UUID embryo) {
    return jdbc.query(
        "SELECT * FROM embryo_evaluation WHERE organization_id=? AND embryo_id=? ORDER BY recorded_at,id",
        EVALUATION,
        tenant,
        embryo);
  }

  public EmbryoEvaluation evaluation(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM embryo_evaluation WHERE organization_id=? AND id=?",
            EVALUATION,
            tenant,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public long embryoCount(UUID tenant, UUID mating) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM embryo WHERE organization_id=? AND mating_id=?",
        Long.class,
        tenant,
        mating);
  }

  public void complete(
      UUID tenant,
      UUID mating,
      int produced,
      List<Disposition> dispositions,
      UUID actor,
      Instant now) {
    jdbc.batchUpdate(
        "INSERT INTO aggregate_embryo_disposition(id,organization_id,mating_id,disposition_code,quantity,reason,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?)",
        dispositions,
        100,
        (ps, d) -> {
          ps.setObject(1, d.id());
          ps.setObject(2, tenant);
          ps.setObject(3, mating);
          ps.setString(4, d.code());
          ps.setInt(5, d.quantity());
          ps.setString(6, d.reason());
          ps.setObject(7, actor);
          ps.setTimestamp(8, Timestamp.from(now));
        });
    int individualized = Math.toIntExact(embryoCount(tenant, mating));
    int aggregate = dispositions.stream().mapToInt(Disposition::quantity).sum();
    jdbc.update(
        "INSERT INTO mating_completion(organization_id,mating_id,produced_count,individualized_count,aggregate_disposition_count,completed_by,completed_at) VALUES (?,?,?,?,?,?,?)",
        tenant,
        mating,
        produced,
        individualized,
        aggregate,
        actor,
        Timestamp.from(now));
  }

  public record Hold(
      UUID id,
      UUID embryoId,
      String type,
      String reason,
      UUID openedBy,
      Instant openedAt,
      UUID releasedBy,
      Instant releasedAt,
      String releaseReason) {}

  public record Disposition(UUID id, String code, int quantity, String reason) {
    public Disposition {
      com.bovina.platform.application.StableIds.requireVersion7(id);
      if (code == null
          || !(code = code.strip().toUpperCase(Locale.ROOT)).matches("[A-Z][A-Z0-9_]{0,47}")
          || quantity <= 0
          || (reason != null && reason.length() > 500))
        throw new com.bovina.platform.application.ApplicationFailure(
            com.bovina.platform.application.ApplicationFailure.Kind.REJECTED,
            "INVALID_AGGREGATE_DISPOSITION",
            "Aggregate disposition is invalid");
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static com.bovina.platform.application.ApplicationFailure missing(String code) {
    return new com.bovina.platform.application.ApplicationFailure(
        com.bovina.platform.application.ApplicationFailure.Kind.NOT_FOUND,
        code,
        "Embryology record not found");
  }
}
