package com.bovina.cryostorage.infrastructure;

import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryStore {
  private final JdbcTemplate jdbc;
  private final StableIds ids;

  public InventoryStore(JdbcTemplate jdbc, StableIds ids) {
    this.jdbc = jdbc;
    this.ids = ids;
  }

  public boolean hasActiveHold(UUID tenant, UUID packageId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM inventory_hold WHERE organization_id=? AND package_id=? AND released_at IS NULL)",
            Boolean.class,
            tenant,
            packageId));
  }

  public boolean hasShipmentReservation(UUID tenant, UUID packageId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM inventory_shipment_reservation WHERE organization_id=? AND package_id=? AND released_at IS NULL)",
            Boolean.class,
            tenant,
            packageId));
  }

  public void reserveShipment(ExecutionContext c, UUID itemId, UUID packageId, Instant now) {
    jdbc.update(
        "INSERT INTO inventory_shipment_reservation(organization_id,shipment_item_id,package_id,reserved_at) VALUES (?,?,?,?)",
        c.tenantId(),
        itemId,
        packageId,
        Timestamp.from(now));
  }

  public boolean ownsShipmentReservation(UUID tenant, UUID itemId, UUID packageId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM inventory_shipment_reservation WHERE organization_id=? AND shipment_item_id=? AND package_id=? AND released_at IS NULL)",
            Boolean.class,
            tenant,
            itemId,
            packageId));
  }

  public void releaseShipment(UUID tenant, UUID itemId, String reason, Instant now) {
    if (jdbc.update(
            "UPDATE inventory_shipment_reservation SET released_at=?,release_reason=? WHERE organization_id=? AND shipment_item_id=? AND released_at IS NULL",
            Timestamp.from(now),
            reason,
            tenant,
            itemId)
        != 1) throw new IllegalStateException("Shipment reservation changed while locked");
  }

  public void appendShipmentMovement(
      ExecutionContext c,
      UUID id,
      UUID itemId,
      UUID packageId,
      long sequence,
      String type,
      UUID from,
      UUID to,
      Instant occurredAt,
      String reason,
      Instant now) {
    jdbc.update(
        "INSERT INTO inventory_movement(id,organization_id,package_id,sequence,movement_type,from_location_id,to_location_id,occurred_at,performed_by,reason,idempotency_key,recorded_at,origin_type,shipment_item_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL',?)",
        id,
        c.tenantId(),
        packageId,
        sequence,
        type,
        from,
        to,
        Timestamp.from(occurredAt),
        c.actorId(),
        reason,
        id,
        Timestamp.from(now),
        itemId);
  }

  public boolean isLatestShipment(UUID tenant, UUID packageId, UUID itemId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT coalesce((SELECT movement_type='SHIP' AND shipment_item_id=? FROM inventory_movement WHERE organization_id=? AND package_id=? ORDER BY sequence DESC LIMIT 1),false)",
            Boolean.class,
            itemId,
            tenant,
            packageId));
  }

  public boolean activeLocation(UUID tenant, UUID establishment, UUID location) {
    var statuses =
        jdbc.query(
            "SELECT status FROM storage_location WHERE organization_id=? AND establishment_id=? AND id=? FOR SHARE",
            (rs, row) -> rs.getString(1),
            tenant,
            establishment,
            location);
    return statuses.size() == 1 && statuses.getFirst().equals("ACTIVE");
  }

  public void appendMovement(
      ExecutionContext c,
      Movement movement,
      long sequence,
      UUID from,
      UUID to,
      UUID reconciliationId,
      Instant now) {
    jdbc.update(
        "INSERT INTO inventory_movement(id,organization_id,package_id,sequence,movement_type,from_location_id,to_location_id,occurred_at,performed_by,reason,idempotency_key,recorded_at,origin_type,reconciliation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL',?)",
        movement.id(),
        c.tenantId(),
        movement.packageId(),
        sequence,
        movement.type(),
        from,
        to,
        Timestamp.from(movement.occurredAt()),
        c.actorId(),
        movement.reason(),
        movement.id(),
        Timestamp.from(now),
        reconciliationId);
  }

  public List<MovementRow> movements(UUID tenant, UUID packageId) {
    return jdbc.query(
        "SELECT id,sequence,movement_type,from_location_id,to_location_id,occurred_at,reason FROM inventory_movement WHERE organization_id=? AND package_id=? ORDER BY sequence",
        (rs, row) ->
            new MovementRow(
                rs.getObject(1, UUID.class),
                rs.getLong(2),
                rs.getString(3),
                rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class),
                rs.getTimestamp(6).toInstant(),
                rs.getString(7)),
        tenant,
        packageId);
  }

  public List<MovementRow> movementsPage(UUID tenant, UUID packageId, int page, int size) {
    return jdbc.query(
        "SELECT id,sequence,movement_type,from_location_id,to_location_id,occurred_at,reason FROM inventory_movement WHERE organization_id=? AND package_id=? ORDER BY sequence LIMIT ? OFFSET ?",
        (rs, row) ->
            new MovementRow(
                rs.getObject(1, UUID.class),
                rs.getLong(2),
                rs.getString(3),
                rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class),
                rs.getTimestamp(6).toInstant(),
                rs.getString(7)),
        tenant,
        packageId,
        size,
        page * size);
  }

  public MovementRow latestMovement(UUID tenant, UUID packageId) {
    var rows =
        jdbc.query(
            "SELECT id,sequence,movement_type,from_location_id,to_location_id,occurred_at,reason FROM inventory_movement WHERE organization_id=? AND package_id=? ORDER BY sequence DESC LIMIT 1",
            (rs, row) ->
                new MovementRow(
                    rs.getObject(1, UUID.class),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getObject(4, UUID.class),
                    rs.getObject(5, UUID.class),
                    rs.getTimestamp(6).toInstant(),
                    rs.getString(7)),
            tenant,
            packageId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void openHold(ExecutionContext c, Hold hold, Instant now) {
    jdbc.update(
        "INSERT INTO inventory_hold(id,organization_id,package_id,hold_type,reason,opened_at,opened_by) VALUES (?,?,?,?,?,?,?)",
        hold.id(),
        c.tenantId(),
        hold.packageId(),
        hold.typeCode(),
        hold.reason(),
        Timestamp.from(now),
        c.actorId());
    holdEvent(c, hold.id(), "OPENED", hold.reason(), now);
  }

  public HoldRow hold(UUID tenant, UUID id) {
    var rows =
        jdbc.query(
            "SELECT package_id,hold_type,reason,opened_at,released_at FROM inventory_hold WHERE organization_id=? AND id=?",
            (rs, row) ->
                new HoldRow(
                    id,
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getTimestamp(4).toInstant(),
                    rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
            tenant,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void releaseHold(ExecutionContext c, UUID id, String reason, Instant now) {
    var changed =
        jdbc.update(
            "UPDATE inventory_hold SET released_at=?,released_by=?,release_reason=? WHERE organization_id=? AND id=? AND released_at IS NULL",
            Timestamp.from(now),
            c.actorId(),
            reason,
            c.tenantId(),
            id);
    if (changed != 1) throw new IllegalStateException("Hold changed while package lock was held");
    holdEvent(c, id, "RELEASED", reason, now);
  }

  private void holdEvent(ExecutionContext c, UUID id, String type, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO inventory_hold_event(id,organization_id,hold_id,event_type,occurred_at,actor_id,reason) VALUES (?,?,?,?,?,?,?)",
        ids.next(),
        c.tenantId(),
        id,
        type,
        Timestamp.from(now),
        c.actorId(),
        reason);
  }

  public void insertThaw(ExecutionContext c, Thaw thaw, UUID withdrawalMovementId, Instant now) {
    jdbc.update(
        "INSERT INTO thaw_event(id,organization_id,package_id,occurred_at,professional_id,result_code,notes,origin_type,recorded_by,recorded_at,protocol_version_id,withdrawal_movement_id) VALUES (?,?,?,?,?,?,?,'MANUAL',?,?,?,?)",
        thaw.id(),
        c.tenantId(),
        thaw.packageId(),
        Timestamp.from(thaw.occurredAt()),
        thaw.professionalId(),
        thaw.resultCode(),
        thaw.notes(),
        c.actorId(),
        Timestamp.from(now),
        thaw.protocolVersionId(),
        withdrawalMovementId);
  }

  public void thawMembers(
      ExecutionContext c,
      UUID thawId,
      UUID packageId,
      List<CryostorageFacts.PackageMember> members,
      Instant now) {
    jdbc.batchUpdate(
        "INSERT INTO thaw_event_item(id,organization_id,thaw_event_id,package_id,package_item_id,embryo_id) VALUES (?,?,?,?,?,?)",
        members,
        100,
        (statement, member) -> {
          statement.setObject(1, ids.next());
          statement.setObject(2, c.tenantId());
          statement.setObject(3, thawId);
          statement.setObject(4, packageId);
          statement.setObject(5, member.id());
          statement.setObject(6, member.embryoId());
        });
    var changed =
        jdbc.batchUpdate(
            "UPDATE package_item SET removed_at=?,removed_by=?,removal_reason='THAWED' WHERE organization_id=? AND id=? AND removed_at IS NULL",
            members,
            100,
            (statement, member) -> {
              statement.setTimestamp(1, Timestamp.from(now));
              statement.setObject(2, c.actorId());
              statement.setObject(3, c.tenantId());
              statement.setObject(4, member.id());
            });
    for (var chunk : changed)
      for (var count : chunk)
        if (count != 1)
          throw new IllegalStateException("Thawed package membership changed unexpectedly");
  }

  public Instant thawEvidence(UUID tenant, UUID thawId, UUID embryoId) {
    var results =
        jdbc.query(
            """
        SELECT t.occurred_at FROM thaw_event_item i
        JOIN thaw_event t ON t.organization_id=i.organization_id AND t.id=i.thaw_event_id
        JOIN inventory_movement m ON m.organization_id=t.organization_id
            AND m.id=t.withdrawal_movement_id AND m.package_id=t.package_id
        WHERE i.organization_id=? AND i.thaw_event_id=? AND i.embryo_id=?
          AND m.movement_type='WITHDRAW' AND m.to_location_id IS NULL
        """,
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            tenant,
            thawId,
            embryoId);
    return results.isEmpty() ? null : results.getFirst();
  }

  public record Movement(
      UUID id,
      UUID packageId,
      String type,
      UUID expectedLocationId,
      UUID destinationId,
      long expectedVersion,
      Instant occurredAt,
      String reason) {}

  public record MovementRow(
      UUID id,
      long sequence,
      String type,
      UUID fromLocationId,
      UUID toLocationId,
      Instant occurredAt,
      String reason) {}

  public record Hold(UUID id, UUID packageId, String typeCode, String reason) {}

  public record HoldRow(
      UUID id,
      UUID packageId,
      String typeCode,
      String reason,
      Instant openedAt,
      Instant releasedAt) {}

  public record Thaw(
      UUID id,
      UUID packageId,
      Instant occurredAt,
      UUID professionalId,
      UUID protocolVersionId,
      String resultCode,
      String notes,
      List<UUID> packageItemIds) {
    public Thaw {
      packageItemIds = List.copyOf(packageItemIds);
    }
  }
}
