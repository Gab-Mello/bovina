package com.bovina.transfer.infrastructure;

import com.bovina.platform.domain.DataProvenance;
import com.bovina.transfer.application.OutcomeWindows;
import com.bovina.transfer.domain.PregnancyCheck;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PregnancyCheckStore {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public PregnancyCheckStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  public void insert(UUID tenant, Collection<PregnancyCheck> checks) {
    jdbc.batchUpdate(
        """
        INSERT INTO pregnancy_check(id,organization_id,transfer_id,checked_at,checked_timezone,
          result,method_code,observations,professional_id,supersedes_check_id,correction_reason,
          origin_type,source_document_id,import_batch_id,api_client_id,recorded_by,recorded_at,
          confirmed_by,confirmed_at,derivation_reference)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        checks,
        200,
        (ps, check) -> bind(ps, tenant, check));
  }

  public Map<UUID, PregnancyCheck> lockChecks(UUID tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return named
        .query(
            "SELECT * FROM pregnancy_check WHERE organization_id=:tenant AND id IN (:ids) ORDER BY id FOR UPDATE",
            Map.of("tenant", tenant, "ids", ids),
            CHECK)
        .stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(PregnancyCheck::id, c -> c));
  }

  public Set<UUID> invalidated(UUID tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) return Set.of();
    return Set.copyOf(
        named.query(
            "SELECT pregnancy_check_id FROM pregnancy_check_invalidation WHERE organization_id=:tenant AND pregnancy_check_id IN (:ids)",
            Map.of("tenant", tenant, "ids", ids),
            (rs, row) -> rs.getObject(1, UUID.class)));
  }

  public void invalidateAll(UUID tenant, Collection<Invalidation> invalidations) {
    jdbc.batchUpdate(
        """
        INSERT INTO pregnancy_check_invalidation(id,organization_id,transfer_id,pregnancy_check_id,
          replacement_check_id,reason,invalidated_by,invalidated_at)
        VALUES (?,?,?,?,?,?,?,?)
        """,
        invalidations,
        200,
        (ps, invalidation) -> {
          ps.setObject(1, invalidation.id());
          ps.setObject(2, tenant);
          ps.setObject(3, invalidation.transferId());
          ps.setObject(4, invalidation.checkId());
          ps.setObject(5, invalidation.replacementCheckId());
          ps.setString(6, invalidation.reason());
          ps.setObject(7, invalidation.actor());
          ps.setTimestamp(8, Timestamp.from(invalidation.at()));
        });
  }

  public PregnancyCheck latest(UUID tenant, UUID transfer) {
    return jdbc
        .query(
            """
            SELECT c.* FROM pregnancy_check c
            WHERE c.organization_id=? AND c.transfer_id=? AND NOT EXISTS (
              SELECT 1 FROM pregnancy_check_invalidation i
              WHERE i.organization_id=c.organization_id AND i.pregnancy_check_id=c.id)
            ORDER BY c.checked_at DESC,c.recorded_at DESC,c.id DESC LIMIT 1
            """,
            CHECK,
            tenant,
            transfer)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<HistoryEntry> history(UUID tenant, UUID transfer, int limit, int offset) {
    return jdbc.query(
        """
        SELECT c.*,i.id invalidation_id,i.reason invalidation_reason,i.invalidated_at
        FROM pregnancy_check c LEFT JOIN pregnancy_check_invalidation i
          ON i.organization_id=c.organization_id AND i.pregnancy_check_id=c.id
        WHERE c.organization_id=? AND c.transfer_id=?
        ORDER BY c.checked_at,c.recorded_at,c.id LIMIT ? OFFSET ?
        """,
        (rs, row) ->
            new HistoryEntry(
                CHECK.mapRow(rs, row),
                rs.getObject("invalidation_id", UUID.class),
                rs.getString("invalidation_reason"),
                instant(rs, "invalidated_at")),
        tenant,
        transfer,
        limit,
        offset);
  }

  public List<FollowUp> followUps(
      UUID tenant,
      OutcomeWindows.Cohort cohort,
      OutcomeWindows.Window window,
      LocalDate asOf,
      int limit,
      int offset) {
    return jdbc.query(
        """
        SELECT t.id transfer_id,t.embryo_id,t.recipient_cycle_id,c.recipient_animal_id,
          (t.performed_at AT TIME ZONE t.performed_timezone)::date performed_on,
          p.id check_id,p.result,p.checked_at,p.checked_timezone
        FROM embryo_transfer t JOIN recipient_cycle c
          ON c.organization_id=t.organization_id AND c.id=t.recipient_cycle_id
        LEFT JOIN LATERAL (
          SELECT pc.* FROM pregnancy_check pc
          WHERE pc.organization_id=t.organization_id AND pc.transfer_id=t.id
            AND NOT EXISTS (SELECT 1 FROM pregnancy_check_invalidation pi
              WHERE pi.organization_id=pc.organization_id AND pi.pregnancy_check_id=pc.id)
            AND ((pc.checked_at AT TIME ZONE pc.checked_timezone)::date
              - (t.performed_at AT TIME ZONE t.performed_timezone)::date) BETWEEN ? AND ?
          ORDER BY pc.checked_at DESC,pc.recorded_at DESC,pc.id DESC LIMIT 1
        ) p ON true
        WHERE t.organization_id=? AND (?::date
          - (t.performed_at AT TIME ZONE t.performed_timezone)::date) BETWEEN ? AND ?
        ORDER BY performed_on,t.id LIMIT ? OFFSET ?
        """,
        (rs, row) ->
            new FollowUp(
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("embryo_id", UUID.class),
                rs.getObject("recipient_cycle_id", UUID.class),
                rs.getObject("recipient_animal_id", UUID.class),
                rs.getObject("performed_on", LocalDate.class),
                cohort,
                window.targetDay(),
                window.fromDay(),
                window.throughDay(),
                rs.getObject("check_id", UUID.class),
                rs.getString("result"),
                instant(rs, "checked_at"),
                rs.getString("checked_timezone")),
        window.fromDay(),
        window.throughDay(),
        tenant,
        asOf,
        window.fromDay(),
        window.throughDay(),
        limit,
        offset);
  }

  private static final RowMapper<PregnancyCheck> CHECK =
      (rs, row) ->
          new PregnancyCheck(
              rs.getObject("id", UUID.class),
              rs.getObject("transfer_id", UUID.class),
              rs.getTimestamp("checked_at").toInstant(),
              rs.getString("checked_timezone"),
              PregnancyCheck.Result.valueOf(rs.getString("result")),
              rs.getString("method_code"),
              rs.getString("observations"),
              rs.getObject("professional_id", UUID.class),
              rs.getObject("supersedes_check_id", UUID.class),
              rs.getString("correction_reason"),
              new DataProvenance(
                  DataProvenance.Origin.valueOf(rs.getString("origin_type")),
                  rs.getObject("source_document_id", UUID.class),
                  rs.getObject("import_batch_id", UUID.class),
                  rs.getObject("api_client_id", UUID.class),
                  rs.getObject("recorded_by", UUID.class),
                  rs.getTimestamp("recorded_at").toInstant(),
                  rs.getObject("confirmed_by", UUID.class),
                  instant(rs, "confirmed_at"),
                  rs.getString("derivation_reference")));

  private static void bind(PreparedStatement ps, UUID tenant, PregnancyCheck check)
      throws SQLException {
    var p = check.provenance();
    ps.setObject(1, check.id());
    ps.setObject(2, tenant);
    ps.setObject(3, check.transferId());
    ps.setTimestamp(4, Timestamp.from(check.checkedAt()));
    ps.setString(5, check.timezone());
    ps.setString(6, check.result().name());
    ps.setString(7, check.methodCode());
    ps.setString(8, check.observations());
    ps.setObject(9, check.professionalId());
    ps.setObject(10, check.supersedesCheckId());
    ps.setString(11, check.correctionReason());
    ps.setString(12, p.originType().name());
    ps.setObject(13, p.sourceDocumentId());
    ps.setObject(14, p.importBatchId());
    ps.setObject(15, p.apiClientId());
    ps.setObject(16, p.recordedByUserId());
    ps.setTimestamp(17, Timestamp.from(p.recordedAt()));
    ps.setObject(18, p.confirmedByUserId());
    ps.setTimestamp(19, p.confirmedAt() == null ? null : Timestamp.from(p.confirmedAt()));
    ps.setString(20, p.derivationReference());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public record Invalidation(
      UUID id,
      UUID transferId,
      UUID checkId,
      UUID replacementCheckId,
      String reason,
      UUID actor,
      Instant at) {}

  public record HistoryEntry(
      PregnancyCheck check,
      UUID invalidationId,
      String invalidationReason,
      Instant invalidatedAt) {}

  public record FollowUp(
      UUID transferId,
      UUID embryoId,
      UUID recipientCycleId,
      UUID recipientAnimalId,
      LocalDate performedOn,
      OutcomeWindows.Cohort cohort,
      int targetDay,
      int windowFromDay,
      int windowThroughDay,
      UUID checkId,
      String result,
      Instant checkedAt,
      String checkedTimezone) {
    public String status() {
      return checkId == null ? "DUE" : "RECORDED";
    }
  }
}
