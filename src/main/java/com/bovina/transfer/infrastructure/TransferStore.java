package com.bovina.transfer.infrastructure;

import com.bovina.platform.domain.DataProvenance;
import com.bovina.transfer.domain.*;
import jakarta.persistence.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferStore {
  private final EntityManager entities;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public TransferStore(
      EntityManager entities, JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.entities = entities;
    this.jdbc = jdbc;
    this.named = named;
  }

  public void persistReservations(Collection<TransferReservation> reservations) {
    reservations.forEach(entities::persist);
    entities.flush();
  }

  public Map<UUID, ReservationRef> reservationRefs(UUID tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return named
        .query(
            "SELECT id,embryo_id,recipient_cycle_id,status,version FROM embryo_transfer_reservation WHERE organization_id=:tenant AND id IN (:ids)",
            Map.of("tenant", tenant, "ids", ids),
            (rs, row) ->
                new ReservationRef(
                    rs.getObject("id", UUID.class),
                    rs.getObject("embryo_id", UUID.class),
                    rs.getObject("recipient_cycle_id", UUID.class),
                    rs.getString("status"),
                    rs.getLong("version")))
        .stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(ReservationRef::id, r -> r));
  }

  public Map<UUID, TransferReservation> lockReservations(UUID tenant, Collection<UUID> ids) {
    var locked =
        entities
            .createQuery(
                "select r from TransferReservation r where r.organizationId=:tenant and r.id in :ids order by r.id",
                TransferReservation.class)
            .setParameter("tenant", tenant)
            .setParameter("ids", ids)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
    return locked.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(TransferReservation::id, r -> r));
  }

  public void flush() {
    entities.flush();
  }

  public void insertTransfers(UUID tenant, Collection<EmbryoTransfer> transfers) {
    jdbc.batchUpdate(
        """
        INSERT INTO embryo_transfer(id,organization_id,reservation_id,embryo_id,recipient_cycle_id,
          performed_at,performed_timezone,transfer_origin,operator_professional_id,notes,
          origin_type,source_document_id,import_batch_id,api_client_id,recorded_by,recorded_at,
          confirmed_by,confirmed_at,derivation_reference,thaw_event_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        transfers,
        100,
        (ps, transfer) -> bindTransfer(ps, tenant, transfer));
  }

  public Optional<EmbryoTransfer> transfer(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM embryo_transfer WHERE organization_id=? AND id=?", TRANSFER, tenant, id)
        .stream()
        .findFirst();
  }

  public Map<UUID, EmbryoTransfer> transfers(UUID tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return named
        .query(
            "SELECT * FROM embryo_transfer WHERE organization_id=:tenant AND id IN (:ids)",
            Map.of("tenant", tenant, "ids", ids),
            TRANSFER)
        .stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(EmbryoTransfer::id, t -> t));
  }

  public List<EmbryoTransfer> transfers(
      UUID tenant, UUID cycle, UUID embryo, int limit, int offset) {
    return jdbc.query(
        """
        SELECT * FROM embryo_transfer WHERE organization_id=?
          AND (CAST(? AS uuid) IS NULL OR recipient_cycle_id=?)
          AND (CAST(? AS uuid) IS NULL OR embryo_id=?)
        ORDER BY performed_at DESC,id LIMIT ? OFFSET ?
        """,
        TRANSFER,
        tenant,
        cycle,
        cycle,
        embryo,
        embryo,
        limit,
        offset);
  }

  private static final RowMapper<EmbryoTransfer> TRANSFER =
      (rs, row) ->
          new EmbryoTransfer(
              rs.getObject("id", UUID.class),
              rs.getObject("reservation_id", UUID.class),
              rs.getObject("embryo_id", UUID.class),
              rs.getObject("recipient_cycle_id", UUID.class),
              rs.getTimestamp("performed_at").toInstant(),
              rs.getString("performed_timezone"),
              EmbryoTransfer.Origin.valueOf(rs.getString("transfer_origin")),
              rs.getObject("thaw_event_id", UUID.class),
              rs.getObject("operator_professional_id", UUID.class),
              rs.getString("notes"),
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

  private static void bindTransfer(PreparedStatement ps, UUID tenant, EmbryoTransfer transfer)
      throws java.sql.SQLException {
    var p = transfer.provenance();
    ps.setObject(1, transfer.id());
    ps.setObject(2, tenant);
    ps.setObject(3, transfer.reservationId());
    ps.setObject(4, transfer.embryoId());
    ps.setObject(5, transfer.recipientCycleId());
    ps.setTimestamp(6, Timestamp.from(transfer.performedAt()));
    ps.setString(7, transfer.timezone());
    ps.setString(8, transfer.origin().name());
    ps.setObject(9, transfer.operatorProfessionalId());
    ps.setString(10, transfer.notes());
    ps.setString(11, p.originType().name());
    ps.setObject(12, p.sourceDocumentId());
    ps.setObject(13, p.importBatchId());
    ps.setObject(14, p.apiClientId());
    ps.setObject(15, p.recordedByUserId());
    ps.setTimestamp(16, Timestamp.from(p.recordedAt()));
    ps.setObject(17, p.confirmedByUserId());
    ps.setTimestamp(18, p.confirmedAt() == null ? null : Timestamp.from(p.confirmedAt()));
    ps.setString(19, p.derivationReference());
    ps.setObject(20, transfer.thawEventId());
  }

  private static Instant instant(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public record ReservationRef(
      UUID id, UUID embryoId, UUID recipientCycleId, String status, long version) {}
}
