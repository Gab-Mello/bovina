package com.bovina.distribution.infrastructure;

import com.bovina.cryostorage.application.ShipmentInventory;
import com.bovina.distribution.application.Shipments;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.SearchPage;
import com.bovina.platform.domain.Address;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShipmentEvidence {
  private final JdbcTemplate jdbc;

  public ShipmentEvidence(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Shipments.Destination destination(UUID tenant, UUID recipient, UUID property) {
    var rows =
        jdbc.query(
            "SELECT id,display_name,legal_name,version,address_line,municipality,state,country,postal_code FROM party p WHERE organization_id=? AND id=? AND status='ACTIVE' AND EXISTS(SELECT 1 FROM party_role r WHERE r.organization_id=p.organization_id AND r.party_id=p.id AND r.role='SHIPMENT_DESTINATION') FOR SHARE OF p",
            (rs, n) ->
                new Shipments.Destination(
                    rs.getString("display_name"),
                    rs.getString("legal_name"),
                    rs.getLong("version"),
                    null,
                    null,
                    rs.getString("address_line") == null
                        ? null
                        : new Address(
                            rs.getString("address_line"),
                            rs.getString("municipality"),
                            rs.getString("state"),
                            rs.getString("country"),
                            rs.getString("postal_code"))),
            tenant,
            recipient);
    if (rows.isEmpty()) throw missing("SHIPMENT_RECIPIENT_NOT_FOUND");
    var result = rows.getFirst();
    if (property != null) {
      var farms =
          jdbc.query(
              "SELECT name,version,address_line,municipality,state,country,postal_code FROM farm_property WHERE organization_id=? AND id=? AND status='ACTIVE' FOR SHARE",
              (rs, n) ->
                  new Shipments.Destination(
                      result.recipientName(),
                      result.legalName(),
                      result.recipientVersion(),
                      rs.getString("name"),
                      rs.getLong("version"),
                      new Address(
                          rs.getString("address_line"),
                          rs.getString("municipality"),
                          rs.getString("state"),
                          rs.getString("country"),
                          rs.getString("postal_code"))),
              tenant,
              property);
      if (farms.isEmpty()) throw missing("DESTINATION_PROPERTY_NOT_FOUND");
      return farms.getFirst();
    }
    if (result.address() == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "SHIPMENT_DESTINATION_ADDRESS_REQUIRED",
          "Use a recipient address or destination property");
    return result;
  }

  public void items(
      UUID tenant,
      UUID shipment,
      List<Shipments.PackageSelection> items,
      Map<UUID, Integer> quantities) {
    jdbc.batchUpdate(
        "INSERT INTO shipment_item(id,organization_id,shipment_id,package_id,expected_package_version,expected_location_id,quantity) VALUES (?,?,?,?,?,?,?)",
        items,
        100,
        (ps, item) -> {
          ps.setObject(1, item.id());
          ps.setObject(2, tenant);
          ps.setObject(3, shipment);
          ps.setObject(4, item.packageId());
          ps.setLong(5, item.expectedVersion());
          ps.setObject(6, item.expectedLocationId());
          ps.setInt(7, quantities.get(item.id()));
        });
  }

  public List<ShipmentInventory.Item> items(UUID tenant, UUID shipment) {
    return jdbc.query(
        "SELECT id,package_id,expected_package_version,expected_location_id,quantity FROM shipment_item WHERE organization_id=? AND shipment_id=? ORDER BY package_id",
        (rs, n) ->
            new ShipmentInventory.Item(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                rs.getObject(4, UUID.class),
                rs.getInt(5)),
        tenant,
        shipment);
  }

  public void documents(UUID tenant, UUID shipment, List<Shipments.DocumentLink> documents) {
    jdbc.batchUpdate(
        "INSERT INTO shipment_document_reference(id,organization_id,shipment_id,type_code,document_id,document_version_id,document_number,issuer,issued_at) VALUES (?,?,?,?,?,?,?,?,?)",
        documents,
        100,
        (ps, d) -> {
          ps.setObject(1, d.id());
          ps.setObject(2, tenant);
          ps.setObject(3, shipment);
          ps.setString(4, d.typeCode());
          ps.setObject(5, d.documentId());
          ps.setObject(6, d.versionId());
          ps.setString(7, d.number());
          ps.setString(8, d.issuer());
          ps.setTimestamp(9, d.issuedAt() == null ? null : Timestamp.from(d.issuedAt()));
        });
  }

  public List<Shipments.DocumentLink> documents(UUID tenant, UUID shipment) {
    return jdbc.query(
        "SELECT id,type_code,document_id,document_version_id,document_number,issuer,issued_at FROM shipment_document_reference WHERE organization_id=? AND shipment_id=? ORDER BY id",
        (rs, n) ->
            new Shipments.DocumentLink(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
        tenant,
        shipment);
  }

  public void dispatch(
      ExecutionContext c,
      UUID shipment,
      Shipments.Destination d,
      Instant occurred,
      Instant now,
      boolean regulated,
      String result,
      String explanation) {
    var a = d.address();
    jdbc.update(
        "INSERT INTO shipment_destination_snapshot(organization_id,shipment_id,recipient_name,recipient_legal_name,recipient_version,property_name,property_version,address_line,municipality,state,country,postal_code,dispatched_at,recorded_at,recorded_by,regulated_dispatch,compliance_result,compliance_explanation) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        c.tenantId(),
        shipment,
        d.recipientName(),
        d.legalName(),
        d.recipientVersion(),
        d.propertyName(),
        d.propertyVersion(),
        a.addressLine(),
        a.municipality(),
        a.state(),
        a.country(),
        a.postalCode(),
        Timestamp.from(occurred),
        Timestamp.from(now),
        c.actorId(),
        regulated,
        result,
        explanation);
  }

  public Shipments.DispatchEvidence dispatch(UUID tenant, UUID shipment) {
    var rows =
        jdbc.query(
            "SELECT * FROM shipment_destination_snapshot WHERE organization_id=? AND shipment_id=?",
            (rs, n) ->
                new Shipments.DispatchEvidence(
                    new Shipments.Destination(
                        rs.getString("recipient_name"),
                        rs.getString("recipient_legal_name"),
                        rs.getLong("recipient_version"),
                        rs.getString("property_name"),
                        rs.getObject("property_version") == null
                            ? null
                            : rs.getLong("property_version"),
                        new Address(
                            rs.getString("address_line"),
                            rs.getString("municipality"),
                            rs.getString("state"),
                            rs.getString("country"),
                            rs.getString("postal_code"))),
                    rs.getTimestamp("dispatched_at").toInstant(),
                    rs.getBoolean("regulated_dispatch"),
                    rs.getString("compliance_result"),
                    rs.getString("compliance_explanation")),
            tenant,
            shipment);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void cancel(ExecutionContext c, UUID shipment, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO shipment_cancellation(organization_id,shipment_id,reason,cancelled_at,cancelled_by) VALUES (?,?,?,?,?)",
        c.tenantId(),
        shipment,
        reason,
        Timestamp.from(now),
        c.actorId());
  }

  public boolean received(UUID tenant, UUID shipment) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM shipment_receipt WHERE organization_id=? AND shipment_id=?)",
            Boolean.class,
            tenant,
            shipment));
  }

  public void receive(
      ExecutionContext c, UUID shipment, Instant received, String notes, Instant now) {
    jdbc.update(
        "INSERT INTO shipment_receipt(organization_id,shipment_id,received_at,notes,recorded_at,recorded_by) VALUES (?,?,?,?,?,?)",
        c.tenantId(),
        shipment,
        Timestamp.from(received),
        notes,
        Timestamp.from(now),
        c.actorId());
  }

  public List<Shipments.Summary> page(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT id,status,version,establishment_id,destination_recipient_id,created_at FROM shipment WHERE organization_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        (rs, n) ->
            new Shipments.Summary(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getLong(3),
                rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class),
                rs.getTimestamp(6).toInstant()),
        tenant,
        page.size(),
        page.offset());
  }

  private static ApplicationFailure missing(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, code, "Shipment destination not found");
  }
}
