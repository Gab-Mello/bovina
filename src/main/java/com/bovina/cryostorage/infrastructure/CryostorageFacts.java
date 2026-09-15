package com.bovina.cryostorage.infrastructure;

import com.bovina.cryostorage.domain.CryopreservationBatch;
import com.bovina.embryology.application.EmbryoCryopreservationBoundary.Snapshot;
import com.bovina.platform.application.ExecutionContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CryostorageFacts {
  private final JdbcTemplate jdbc;

  public CryostorageFacts(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> previouslyCryopreserved(UUID tenant, Collection<UUID> embryos) {
    if (embryos.isEmpty()) return List.of();
    var placeholders = String.join(",", java.util.Collections.nCopies(embryos.size(), "?"));
    var values = new ArrayList<Object>();
    values.add(tenant);
    values.addAll(embryos);
    return jdbc.query(
        "SELECT DISTINCT embryo_id FROM cryopreservation_item WHERE organization_id=? AND embryo_id IN ("
            + placeholders
            + ")",
        (rs, row) -> rs.getObject(1, UUID.class),
        values.toArray());
  }

  public void insertCryopreservation(
      ExecutionContext c, CryopreservationBatch batch, Map<UUID, Snapshot> snapshots, Instant now) {
    jdbc.update(
        "INSERT INTO cryopreservation_event(id,organization_id,establishment_id,occurred_at,method_code,protocol_version_id,professional_id,batch_reference,notes,origin_type,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,'MANUAL',?,?)",
        batch.eventId(),
        c.tenantId(),
        batch.establishmentId(),
        Timestamp.from(batch.occurredAt()),
        batch.methodCode(),
        batch.protocolVersionId(),
        batch.professionalId(),
        batch.batchReference(),
        batch.notes(),
        c.actorId(),
        Timestamp.from(now));
    jdbc.batchUpdate(
        "INSERT INTO cryopreservation_item(id,organization_id,event_id,embryo_id,evaluation_id,stage_code,result_code) VALUES (?,?,?,?,?,?,'PRESERVED')",
        batch.items(),
        100,
        (statement, item) -> {
          var embryo = snapshots.get(item.embryoId());
          statement.setObject(1, item.id());
          statement.setObject(2, c.tenantId());
          statement.setObject(3, batch.eventId());
          statement.setObject(4, item.embryoId());
          statement.setObject(5, embryo.evaluationId());
          statement.setString(6, embryo.stageCode());
        });
  }

  public Map<UUID, CryoItem> preservedItems(UUID tenant, Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) return Map.of();
    var placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
    var values = new ArrayList<Object>();
    values.add(tenant);
    values.addAll(itemIds);
    var result = new HashMap<UUID, CryoItem>();
    jdbc.query(
        "SELECT i.id,i.embryo_id,e.establishment_id FROM cryopreservation_item i JOIN cryopreservation_event e ON e.organization_id=i.organization_id AND e.id=i.event_id WHERE i.organization_id=? AND i.result_code='PRESERVED' AND i.id IN ("
            + placeholders
            + ")",
        rs -> {
          var item =
              new CryoItem(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getObject(3, UUID.class));
          result.put(item.id(), item);
        },
        values.toArray());
    return Map.copyOf(result);
  }

  public void insertPackageMembers(
      UUID tenant, UUID packageId, Collection<PackageMember> members, UUID actor, Instant now) {
    jdbc.batchUpdate(
        "INSERT INTO package_item(id,organization_id,package_id,cryopreservation_item_id,embryo_id,position_code,added_at,added_by) VALUES (?,?,?,?,?,?,?,?)",
        members,
        100,
        (statement, member) -> {
          statement.setObject(1, member.id());
          statement.setObject(2, tenant);
          statement.setObject(3, packageId);
          statement.setObject(4, member.cryopreservationItemId());
          statement.setObject(5, member.embryoId());
          statement.setString(6, member.positionCode());
          statement.setTimestamp(7, Timestamp.from(now));
          statement.setObject(8, actor);
        });
  }

  public List<PackageMember> activeMembers(UUID tenant, UUID packageId) {
    return jdbc.query(
        "SELECT id,cryopreservation_item_id,embryo_id,position_code FROM package_item WHERE organization_id=? AND package_id=? AND removed_at IS NULL ORDER BY id",
        (rs, row) ->
            new PackageMember(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4)),
        tenant,
        packageId);
  }

  public int activeMemberCount(UUID tenant, UUID packageId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM package_item WHERE organization_id=? AND package_id=? AND removed_at IS NULL",
        Integer.class,
        tenant,
        packageId);
  }

  public record CryoItem(UUID id, UUID embryoId, UUID establishmentId) {}

  public record PackageMember(
      UUID id, UUID cryopreservationItemId, UUID embryoId, String positionCode) {}
}
