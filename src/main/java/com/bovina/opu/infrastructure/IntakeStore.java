package com.bovina.opu.infrastructure;

import com.bovina.opu.application.ExternalReceiptView;
import com.bovina.opu.application.TransportView;
import com.bovina.opu.domain.*;
import com.bovina.parties.application.FarmOrigin;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.domain.DataProvenance;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class IntakeStore {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public IntakeStore(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void transport(ExecutionContext c, TransportReceipt r, Instant now) {
    jdbc.update(
        "INSERT INTO oocyte_transport(id,organization_id,source_session_id,destination_establishment_id,destination_operational_location_id,dispatched_at,received_at,protocol_version_id,protocol_applied_on,source_document_id,notes,recorded_by,recorded_at,origin_type) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL')",
        r.id(),
        c.tenantId(),
        r.sourceSessionId(),
        r.destinationEstablishmentId(),
        r.destinationOperationalLocationId(),
        r.dispatchedAt() == null ? null : Timestamp.from(r.dispatchedAt()),
        Timestamp.from(r.receivedAt()),
        r.protocolVersionId(),
        r.protocolAppliedOn(),
        r.sourceDocumentId(),
        r.notes(),
        c.actorId(),
        Timestamp.from(now));
    jdbc.batchUpdate(
        "INSERT INTO oocyte_transport_item(organization_id,transport_id,session_id,collection_id,quantity_at_dispatch,quantity_at_receipt) VALUES (?,?,?,?,?,?)",
        r.items(),
        100,
        (ps, i) -> {
          ps.setObject(1, c.tenantId());
          ps.setObject(2, r.id());
          ps.setObject(3, r.sourceSessionId());
          ps.setObject(4, i.collectionId());
          ps.setObject(5, i.quantityAtDispatch());
          ps.setObject(6, i.quantityAtReceipt());
        });
  }

  public Optional<TransportView> transport(UUID tenant, UUID id) {
    var items =
        jdbc.query(
            "SELECT collection_id,quantity_at_dispatch,quantity_at_receipt FROM oocyte_transport_item WHERE organization_id=? AND transport_id=? ORDER BY collection_id",
            (rs, n) ->
                new TransportReceipt.Item(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, Integer.class),
                    rs.getObject(3, Integer.class)),
            tenant,
            id);
    return jdbc
        .query(
            "SELECT * FROM oocyte_transport WHERE organization_id=? AND id=?",
            (rs, n) -> {
              var dispatch = rs.getTimestamp("dispatched_at");
              var r =
                  new TransportReceipt(
                      id,
                      rs.getObject("source_session_id", UUID.class),
                      rs.getObject("destination_establishment_id", UUID.class),
                      rs.getObject("destination_operational_location_id", UUID.class),
                      dispatch == null ? null : dispatch.toInstant(),
                      rs.getTimestamp("received_at").toInstant(),
                      rs.getObject("protocol_version_id", UUID.class),
                      rs.getObject("protocol_applied_on", LocalDate.class),
                      rs.getObject("source_document_id", UUID.class),
                      rs.getString("notes"),
                      items);
              return new TransportView(
                  r,
                  provenance(
                      r.sourceDocumentId(),
                      rs.getObject("recorded_by", UUID.class),
                      rs.getTimestamp("recorded_at").toInstant()));
            },
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public void external(
      ExecutionContext c, ExternalOocyteReceipt r, FarmOrigin snapshot, Instant now) {
    jdbc.update(
        "INSERT INTO external_oocyte_receipt(id,organization_id,received_at,source_reference,source_farm_property_id,farm_snapshot,owner_id,collecting_professional_id,total_received,source_document_id,notes,status,origin_type,recorded_by,recorded_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,'RECEIVED','MANUAL',?,?)",
        r.id(),
        c.tenantId(),
        Timestamp.from(r.receivedAt()),
        r.sourceReference(),
        r.sourceFarmPropertyId(),
        snapshot == null ? null : json.writeValueAsString(snapshot),
        r.ownerId(),
        r.collectingProfessionalId(),
        r.totalReceived(),
        r.sourceDocumentId(),
        r.notes(),
        c.actorId(),
        Timestamp.from(now));
  }

  public Optional<ExternalReceiptView> external(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM external_oocyte_receipt WHERE organization_id=? AND id=?",
            (rs, n) -> {
              var r =
                  new ExternalOocyteReceipt(
                      id,
                      rs.getTimestamp("received_at").toInstant(),
                      rs.getString("source_reference"),
                      rs.getObject("source_farm_property_id", UUID.class),
                      rs.getObject("owner_id", UUID.class),
                      rs.getObject("collecting_professional_id", UUID.class),
                      rs.getInt("total_received"),
                      rs.getObject("source_document_id", UUID.class),
                      rs.getString("notes"));
              var snapshot = rs.getString("farm_snapshot");
              return new ExternalReceiptView(
                  r,
                  "RECEIVED",
                  snapshot == null ? null : json.readValue(snapshot, FarmOrigin.class),
                  provenance(
                      r.sourceDocumentId(),
                      rs.getObject("recorded_by", UUID.class),
                      rs.getTimestamp("recorded_at").toInstant()));
            },
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public static DataProvenance provenance(UUID document, UUID actor, Instant now) {
    return new DataProvenance(
        DataProvenance.Origin.MANUAL, document, null, null, actor, now, null, null, null);
  }
}
