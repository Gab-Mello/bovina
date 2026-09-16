package com.bovina.distribution.infrastructure;

import com.bovina.cryostorage.application.ShipmentInventory;
import com.bovina.distribution.application.Recalls;
import com.bovina.platform.application.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecallStore {
  private final JdbcTemplate jdbc;

  public RecallStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void requireTrigger(UUID tenant, String type, UUID id) {
    var table =
        switch (type) {
          case "DONOR" -> "animal";
          case "SEMEN_BATCH" -> "semen_batch";
          case "MATING" -> "mating";
          case "PACKAGE" -> "embryo_package";
          case "OOCYTE_COLLECTION" -> "oocyte_collection";
          case "CRYOPRESERVATION_EVENT" -> "cryopreservation_event";
          default -> throw new IllegalArgumentException("Unsupported recall trigger");
        };
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE organization_id=? AND id=?)",
            Boolean.class,
            tenant,
            id)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND, "RECALL_TRIGGER_NOT_FOUND", "Recall source not found");
  }

  public void insert(ExecutionContext c, Recalls.Open input, Instant now) {
    var t = input.triggerType();
    jdbc.update(
        "INSERT INTO recall_case(id,organization_id,trigger_type,trigger_id,donor_id,semen_batch_id,mating_id,package_id,collection_id,cryopreservation_event_id,source_document_id,reason,opened_at,opened_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        input.id(),
        c.tenantId(),
        t,
        input.triggerId(),
        t.equals("DONOR") ? input.triggerId() : null,
        t.equals("SEMEN_BATCH") ? input.triggerId() : null,
        t.equals("MATING") ? input.triggerId() : null,
        t.equals("PACKAGE") ? input.triggerId() : null,
        t.equals("OOCYTE_COLLECTION") ? input.triggerId() : null,
        t.equals("CRYOPRESERVATION_EVENT") ? input.triggerId() : null,
        input.sourceDocumentId(),
        input.reason(),
        Timestamp.from(now),
        c.actorId());
  }

  public Recalls.CaseView find(UUID tenant, UUID id) {
    var rows =
        jdbc.query(
            "SELECT id,trigger_type,trigger_id,reason,opened_at,source_document_id FROM recall_case WHERE organization_id=? AND id=?",
            (rs, n) ->
                new Recalls.CaseView(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant(),
                    rs.getObject(6, UUID.class)),
            tenant,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<Recalls.CaseView> page(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT id,trigger_type,trigger_id,reason,opened_at,source_document_id FROM recall_case WHERE organization_id=? AND (reason ILIKE ? OR trigger_type ILIKE ? OR id::text ILIKE ?) ORDER BY opened_at DESC,id LIMIT ? OFFSET ?",
        (rs, n) ->
            new Recalls.CaseView(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getTimestamp(5).toInstant(),
                rs.getObject(6, UUID.class)),
        tenant,
        page.pattern(),
        page.pattern(),
        page.pattern(),
        page.size(),
        page.offset());
  }

  private String affected(String type) {
    var criterion =
        switch (type) {
          case "DONOR" -> "c.donor_id=?";
          case "SEMEN_BATCH" -> "m.semen_batch_id=?";
          case "MATING" -> "m.id=?";
          case "OOCYTE_COLLECTION" -> "c.id=?";
          case "PACKAGE" ->
              "EXISTS(SELECT 1 FROM package_item px WHERE px.organization_id=e.organization_id AND px.embryo_id=e.id AND px.package_id=?)";
          case "CRYOPRESERVATION_EVENT" ->
              "EXISTS(SELECT 1 FROM cryopreservation_item cx WHERE cx.organization_id=e.organization_id AND cx.embryo_id=e.id AND cx.event_id=?)";
          default -> throw new IllegalArgumentException("Unsupported recall trigger");
        };
    return "WITH affected AS (SELECT e.id,e.organization_id,e.mating_id,e.availability_status,m.semen_batch_id,s.sire_id,c.donor_id FROM embryo e JOIN mating m ON m.organization_id=e.organization_id AND m.id=e.mating_id JOIN oocyte_collection c ON c.organization_id=m.organization_id AND c.id=m.oocyte_collection_id JOIN semen_batch s ON s.organization_id=m.organization_id AND s.id=m.semen_batch_id WHERE e.organization_id=? AND "
        + criterion
        + " AND e.recorded_at<=?) ";
  }

  public List<Recalls.MaterialImpact> impact(
      UUID tenant, Recalls.CaseView recall, Instant cutoff, SearchPage page) {
    return jdbc.query(
        affected(recall.triggerType())
            + """
      SELECT a.id,a.mating_id,a.donor_id,a.semen_batch_id,a.sire_id,a.availability_status,
          p.id AS package_id,pi.removed_at IS NULL AND pi.id IS NOT NULL AS active_membership,
          p.current_location_id,p.version AS package_version,l.to_location_id AS location_at_cutoff,
          si.shipment_id,d.recipient_name,d.property_name,d.address_line,d.municipality,d.state,d.country,d.dispatched_at,
          t.id AS transfer_id,
          EXISTS(SELECT 1 FROM inventory_hold h WHERE h.organization_id=a.organization_id AND h.package_id=p.id AND h.released_at IS NULL) AS held
      FROM affected a
      LEFT JOIN package_item pi ON pi.organization_id=a.organization_id AND pi.embryo_id=a.id AND pi.added_at<=?
      LEFT JOIN embryo_package p ON p.organization_id=pi.organization_id AND p.id=pi.package_id
      LEFT JOIN LATERAL (SELECT to_location_id FROM inventory_movement im WHERE im.organization_id=p.organization_id AND im.package_id=p.id AND im.recorded_at<=? ORDER BY sequence DESC LIMIT 1) l ON true
      LEFT JOIN shipment_item si ON si.organization_id=p.organization_id AND si.package_id=p.id
          AND EXISTS(SELECT 1 FROM shipment_destination_snapshot ds WHERE ds.organization_id=si.organization_id AND ds.shipment_id=si.shipment_id AND ds.recorded_at<=?)
          AND NOT EXISTS (
              SELECT 1 FROM thaw_event_item ti
              JOIN thaw_event te ON te.organization_id=ti.organization_id AND te.id=ti.thaw_event_id
              JOIN inventory_movement withdrawal ON withdrawal.organization_id=te.organization_id AND withdrawal.id=te.withdrawal_movement_id
              JOIN inventory_movement sent ON sent.organization_id=si.organization_id AND sent.shipment_item_id=si.id AND sent.movement_type='SHIP'
              WHERE ti.organization_id=pi.organization_id AND ti.package_item_id=pi.id AND withdrawal.sequence<sent.sequence
          )
      LEFT JOIN shipment_destination_snapshot d ON d.organization_id=si.organization_id AND d.shipment_id=si.shipment_id
      LEFT JOIN embryo_transfer t ON t.organization_id=a.organization_id AND t.embryo_id=a.id AND t.recorded_at<=?
      ORDER BY a.id,p.id,si.shipment_id LIMIT ? OFFSET ?
      """,
        (rs, n) ->
            new Recalls.MaterialImpact(
                rs.getObject("id", UUID.class),
                rs.getObject("mating_id", UUID.class),
                rs.getObject("donor_id", UUID.class),
                rs.getObject("semen_batch_id", UUID.class),
                rs.getObject("sire_id", UUID.class),
                rs.getString("availability_status"),
                rs.getObject("package_id", UUID.class),
                rs.getBoolean("active_membership"),
                rs.getObject("current_location_id", UUID.class),
                rs.getObject("package_version") == null ? null : rs.getLong("package_version"),
                rs.getObject("location_at_cutoff", UUID.class),
                rs.getObject("shipment_id", UUID.class),
                rs.getString("recipient_name"),
                rs.getString("property_name"),
                rs.getString("address_line"),
                rs.getString("municipality"),
                rs.getString("state"),
                rs.getString("country"),
                rs.getTimestamp("dispatched_at") == null
                    ? null
                    : rs.getTimestamp("dispatched_at").toInstant(),
                rs.getObject("transfer_id", UUID.class),
                rs.getBoolean("held")),
        tenant,
        recall.triggerId(),
        Timestamp.from(cutoff),
        Timestamp.from(cutoff),
        Timestamp.from(cutoff),
        Timestamp.from(cutoff),
        Timestamp.from(cutoff),
        page.size(),
        page.offset());
  }

  public Map<UUID, Set<UUID>> affectedMembers(
      UUID tenant, Recalls.CaseView recall, Instant cutoff, List<UUID> packages) {
    var arguments =
        new ArrayList<Object>(
            List.of(tenant, recall.triggerId(), Timestamp.from(cutoff), Timestamp.from(cutoff)));
    arguments.addAll(packages);
    var result = new HashMap<UUID, Set<UUID>>();
    jdbc.query(
        affected(recall.triggerType())
            + "SELECT DISTINCT pi.package_id,a.id AS embryo_id FROM affected a JOIN package_item pi ON pi.organization_id=a.organization_id AND pi.embryo_id=a.id WHERE pi.added_at<=? AND pi.package_id IN ("
            + String.join(",", Collections.nCopies(packages.size(), "?"))
            + ")",
        rs -> {
          result
              .computeIfAbsent(rs.getObject("package_id", UUID.class), ignored -> new HashSet<>())
              .add(rs.getObject("embryo_id", UUID.class));
        },
        arguments.toArray());
    return result;
  }

  public void holdExecution(
      ExecutionContext c,
      UUID id,
      UUID recall,
      Instant cutoff,
      List<ShipmentInventory.HoldResult> results,
      Instant now) {
    jdbc.update(
        "INSERT INTO recall_hold_execution(id,organization_id,recall_case_id,analysis_cutoff,executed_at,executed_by) VALUES (?,?,?,?,?,?)",
        id,
        c.tenantId(),
        recall,
        Timestamp.from(cutoff),
        Timestamp.from(now),
        c.actorId());
    jdbc.batchUpdate(
        "INSERT INTO recall_hold_result(organization_id,execution_id,package_id,outcome,hold_id,observed_version,observed_location_id) VALUES (?,?,?,?,?,?,?)",
        results,
        100,
        (ps, r) -> {
          ps.setObject(1, c.tenantId());
          ps.setObject(2, id);
          ps.setObject(3, r.packageId());
          ps.setString(4, r.outcome());
          ps.setObject(5, r.holdId());
          ps.setLong(6, r.observedVersion());
          ps.setObject(7, r.observedLocation());
        });
  }

  public Recalls.HoldExecution execution(UUID tenant, UUID recall, UUID id) {
    var cutoffs =
        jdbc.query(
            "SELECT analysis_cutoff FROM recall_hold_execution WHERE organization_id=? AND recall_case_id=? AND id=?",
            (rs, n) -> rs.getTimestamp(1).toInstant(),
            tenant,
            recall,
            id);
    if (cutoffs.isEmpty()) return null;
    var rows =
        jdbc.query(
            "SELECT package_id,hold_id,outcome,observed_version,observed_location_id FROM recall_hold_result WHERE organization_id=? AND execution_id=? ORDER BY package_id",
            (rs, n) ->
                new ShipmentInventory.HoldResult(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getLong(4),
                    rs.getObject(5, UUID.class)),
            tenant,
            id);
    return new Recalls.HoldExecution(id, cutoffs.getFirst(), rows);
  }
}
