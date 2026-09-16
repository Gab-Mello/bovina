package com.bovina.compliance.infrastructure;

import com.bovina.compliance.application.RecordCorrections;
import com.bovina.compliance.application.RecordCorrections.Impact;
import com.bovina.compliance.application.RecordCorrections.View;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.SearchPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class CorrectionStore {
  private static final RowMapper<View> ROW =
      (rs, n) ->
          new View(
              rs.getObject("id", UUID.class),
              rs.getString("subject_type"),
              rs.getObject("subject_id", UUID.class),
              rs.getString("reason"),
              rs.getString("previous_semantics"),
              rs.getString("proposed_change"),
              rs.getObject("subject_version") == null ? null : rs.getLong("subject_version"),
              rs.getString("effective_status"),
              rs.getTimestamp("requested_at").toInstant());
  private static final String SELECT =
      "SELECT c.*,coalesce(r.decision,c.status) AS effective_status FROM record_correction c LEFT JOIN record_correction_review r ON r.organization_id=c.organization_id AND r.correction_id=c.id";
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public CorrectionStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Subject subject(UUID tenant, String type, UUID id) {
    var sql =
        switch (type) {
          case "MATING" ->
              "SELECT version,jsonb_build_object('semenBatchId',semen_batch_id,'fertilizedAt',fertilized_at,'collectionId',oocyte_collection_id,'status',status)::text FROM mating WHERE organization_id=? AND id=? FOR UPDATE";
          case "CRYOPRESERVATION_ITEM" ->
              "SELECT NULL::bigint,jsonb_build_object('eventId',event_id,'embryoId',embryo_id,'evaluationId',evaluation_id,'stageCode',stage_code,'resultCode',result_code)::text FROM cryopreservation_item WHERE organization_id=? AND id=?";
          case "PACKAGE_ITEM" ->
              "SELECT NULL::bigint,jsonb_build_object('packageId',package_id,'cryoItemId',cryopreservation_item_id,'embryoId',embryo_id,'removedAt',removed_at)::text FROM package_item WHERE organization_id=? AND id=?";
          case "EMBRYO_TRANSFER" ->
              "SELECT NULL::bigint,jsonb_build_object('embryoId',embryo_id,'recipientCycleId',recipient_cycle_id,'performedAt',performed_at,'thawEventId',thaw_event_id)::text FROM embryo_transfer WHERE organization_id=? AND id=?";
          case "PREGNANCY_CHECK" ->
              "SELECT NULL::bigint,jsonb_build_object('transferId',transfer_id,'checkedAt',checked_at,'result',result,'supersedesCheckId',supersedes_check_id)::text FROM pregnancy_check WHERE organization_id=? AND id=?";
          default -> throw new IllegalArgumentException("Unsupported correction subject");
        };
    var rows =
        jdbc.query(
            sql,
            (rs, n) -> new Subject(rs.getObject(1) == null ? null : rs.getLong(1), rs.getString(2)),
            tenant,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void insert(
      ExecutionContext c, RecordCorrections.Request input, Subject subject, Instant now) {
    var type = input.subjectType();
    jdbc.update(
        "INSERT INTO record_correction(id,organization_id,subject_type,subject_id,reason,proposed_change,status,requested_by,requested_at,mating_id,cryopreservation_item_id,package_item_id,transfer_id,pregnancy_check_id,previous_semantics,subject_version,source_document_id) VALUES (?,?,?,?,?,?::jsonb,'REQUESTED',?,?,?,?,?,?,?,?::jsonb,?,?)",
        input.id(),
        c.tenantId(),
        type,
        input.subjectId(),
        input.reason(),
        json.writeValueAsString(input.proposal()),
        c.actorId(),
        Timestamp.from(now),
        type.equals("MATING") ? input.subjectId() : null,
        type.equals("CRYOPRESERVATION_ITEM") ? input.subjectId() : null,
        type.equals("PACKAGE_ITEM") ? input.subjectId() : null,
        type.equals("EMBRYO_TRANSFER") ? input.subjectId() : null,
        type.equals("PREGNANCY_CHECK") ? input.subjectId() : null,
        subject.semantics(),
        subject.version(),
        input.sourceDocumentId());
  }

  public View find(UUID tenant, UUID id, boolean lock) {
    var rows =
        jdbc.query(
            SELECT + " WHERE c.organization_id=? AND c.id=?" + (lock ? " FOR UPDATE OF c" : ""),
            ROW,
            tenant,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<View> page(UUID tenant, SearchPage page) {
    return jdbc.query(
        SELECT + " WHERE c.organization_id=? ORDER BY c.requested_at DESC,c.id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        page.size(),
        page.offset());
  }

  public void reject(
      ExecutionContext c, UUID reviewId, UUID correctionId, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO record_correction_review(id,organization_id,correction_id,decision,reason,reviewed_by,reviewed_at) VALUES (?,?,?,'REJECTED',?,?,?)",
        reviewId,
        c.tenantId(),
        correctionId,
        reason,
        c.actorId(),
        Timestamp.from(now));
  }

  public void requireSemenBatch(UUID tenant, UUID id) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM semen_batch WHERE organization_id=? AND id=?)",
            Boolean.class,
            tenant,
            id)))
      throw new com.bovina.platform.application.ApplicationFailure(
          com.bovina.platform.application.ApplicationFailure.Kind.NOT_FOUND,
          "SEMEN_BATCH_NOT_FOUND",
          "Semen batch not found");
  }

  public Impact impact(UUID tenant, String type, UUID subjectId) {
    var affected =
        switch (type) {
          case "MATING" -> "SELECT id FROM embryo WHERE organization_id=? AND mating_id=?";
          case "CRYOPRESERVATION_ITEM" ->
              "SELECT embryo_id AS id FROM cryopreservation_item WHERE organization_id=? AND id=?";
          case "PACKAGE_ITEM" ->
              "SELECT embryo_id AS id FROM package_item WHERE organization_id=? AND id=?";
          case "EMBRYO_TRANSFER" ->
              "SELECT embryo_id AS id FROM embryo_transfer WHERE organization_id=? AND id=?";
          case "PREGNANCY_CHECK" ->
              "SELECT t.embryo_id AS id FROM pregnancy_check pc JOIN embryo_transfer t ON t.organization_id=pc.organization_id AND t.id=pc.transfer_id WHERE pc.organization_id=? AND pc.id=?";
          default -> throw new IllegalArgumentException("Unsupported correction subject");
        };
    return jdbc.queryForObject(
        "WITH affected AS ("
            + affected
            + ") SELECT (SELECT count(*) FROM affected),(SELECT count(DISTINCT p.package_id) FROM package_item p JOIN affected a ON a.id=p.embryo_id WHERE p.organization_id=? AND p.removed_at IS NULL),(SELECT count(*) FROM embryo_transfer t JOIN affected a ON a.id=t.embryo_id WHERE t.organization_id=?)",
        (rs, n) ->
            new Impact(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                "BLOCKED_BY_DOMAIN_VALIDATION_APPROVAL_AND_REMEDIATION_POLICY"),
        tenant,
        subjectId,
        tenant,
        tenant);
  }

  public record Subject(Long version, String semantics) {}
}
