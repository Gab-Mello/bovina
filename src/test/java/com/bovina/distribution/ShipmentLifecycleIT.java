package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.support.fixture.DistributionFixtures;
import com.bovina.support.integration.OperationalDistributionTest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ShipmentLifecycleIT extends OperationalDistributionTest {
  @Test
  void dispatchFreezesDestinationAndDocumentVersionsAndReturnOnlyRestoresPhysicalCustody()
      throws Exception {
    var tenant = tenant("Dispatch Evidence Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var document = id();
    var documentVersion = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/documents",
            document,
            Map.of("id", document, "typeCode", "SOURCE_EVIDENCE")),
        201);
    var bytes = "dispatch evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertStatus(
        api.putDocumentVersion(
            tenant.id(), document, documentVersion, 0, "invoice.txt", "text/plain", bytes),
        201);
    var shipment = id();
    var item = DistributionFixtures.item(stock);
    var input =
        DistributionFixtures.preparation(
            shipment,
            recipient,
            stock,
            List.of(item),
            List.of(
                Map.of(
                    "id",
                    id(),
                    "typeCode",
                    "FISCAL_INVOICE",
                    "documentId",
                    document,
                    "versionId",
                    documentVersion)));
    assertStatus(api.post(tenant.id(), "/shipments", shipment, input), 201);
    var dispatchKey = id();
    var dispatched =
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":dispatch",
            dispatchKey,
            DistributionFixtures.dispatch());
    assertStatus(dispatched, 200);
    var evidence = json.readTree(dispatched.body()).path("dispatchEvidence");
    assertThat(evidence.path("complianceResult").asString()).isEqualTo("UNKNOWN");
    assertThat(evidence.path("regulatedDispatch").asBoolean()).isFalse();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":dispatch",
            dispatchKey,
            DistributionFixtures.dispatch()),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":dispatch",
            dispatchKey,
            Map.of("expectedVersion", 0, "occurredAt", Instant.EPOCH)),
        409);
    var updated =
        api.post(
            tenant.id(),
            "/clients/" + recipient + ":update",
            Map.of(
                "expectedVersion",
                1,
                "displayName",
                "Renamed destination",
                "legalName",
                "New legal name",
                "address",
                Map.of(
                    "addressLine",
                    "New address",
                    "municipality",
                    "New city",
                    "state",
                    "SP",
                    "country",
                    "BR")));
    assertStatus(updated, 200);
    var laterVersion = id();
    assertStatus(
        api.putDocumentVersion(
            tenant.id(),
            document,
            laterVersion,
            1,
            "revision.txt",
            "text/plain",
            "revision".getBytes()),
        201);
    var later = api.get(tenant.id(), "/shipments/" + shipment);
    assertStatus(later, 200);
    assertThat(json.readTree(later.body()).path("dispatchEvidence")).isEqualTo(evidence);
    assertThat(json.readTree(later.body()).path("documents").get(0).path("versionId").asString())
        .isEqualTo(documentVersion.toString());
    assertThat(api.getDocumentContent(tenant.id(), document, documentVersion).body())
        .isEqualTo(bytes);
    var receiptKey = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":receive",
            receiptKey,
            Map.of(
                "expectedVersion",
                1,
                "receivedAt",
                Instant.parse("2026-09-03T12:00:00Z"),
                "notes",
                "Observed acknowledgment only")),
        200);
    var returnKey = id();
    var returned =
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":return",
            returnKey,
            Map.of(
                "movementId",
                returnKey,
                "itemId",
                item.get("id"),
                "locationId",
                stock.location(),
                "expectedShipmentVersion",
                1,
                "expectedPackageVersion",
                4,
                "occurredAt",
                Instant.parse("2026-09-04T12:00:00Z"),
                "reason",
                "Physical return observed"));
    assertStatus(returned, 200);
    assertThat(json.readTree(returned.body()).path("status").asString()).isEqualTo("SHIPPED");
    assertThat(api.get(tenant.id(), "/embryos/" + stock.production().embryos().getFirst()).body())
        .contains("SHIPPED_OUT");
    var ledger =
        api.get(tenant.id(), "/inventory-movements/packages/" + stock.packaged().packageId());
    assertStatus(ledger, 200);
    assertThat(ledger.body()).contains("SHIP", "RETURN");
    assertThat(
            api.get(
                    tenant.id(),
                    "/inventory-movements/packages/"
                        + stock.packaged().packageId()
                        + "/projection-check")
                .body())
        .contains("\"matchesLedger\":true");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND shipment_item_id=?",
                Integer.class,
                tenant.id(),
                item.get("id")))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM shipment_destination_snapshot WHERE organization_id=? AND shipment_id=?",
                    tenant.id(),
                    shipment))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void cancellationReleasesReservationWithoutCreatingPhysicalMovement() throws Exception {
    var tenant = tenant("Cancellation Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    var item = DistributionFixtures.item(stock);
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(shipment, recipient, stock, List.of(item), List.of())),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":cancel",
            Map.of("expectedVersion", 0, "reason", "Dispatch abandoned")),
        200);
    assertStatus(
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch()),
        409);
    var next = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            next,
            DistributionFixtures.preparation(
                next, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id()))
        .isZero();
  }
}
