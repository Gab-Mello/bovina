package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.support.fixture.*;
import com.bovina.support.integration.OperationalDistributionTest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecallImpactIT extends OperationalDistributionTest {
  @Test
  void canonicalTriggersFindCurrentMaterialAndHistoricalShipmentDestinations() throws Exception {
    var tenant = tenant("Recall Lineage Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var stored =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(0), "Stored rack");
    var shipped =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(1), "Dispatch rack");
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    var item = DistributionFixtures.item(shipped);
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, shipped, List.of(item), List.of())),
        201);
    assertStatus(
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch()),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/clients/" + recipient + ":update",
            Map.of("expectedVersion", 1, "displayName", "Changed master name")),
        200);
    var triggers =
        Map.of(
            "DONOR",
            production.donor(),
            "SEMEN_BATCH",
            production.semenBatch(),
            "MATING",
            production.mating(),
            "PACKAGE",
            shipped.packaged().packageId(),
            "OOCYTE_COLLECTION",
            production.collection(),
            "CRYOPRESERVATION_EVENT",
            shipped.packaged().cryo().cryoEvent());
    UUID donorRecall = null;
    for (var trigger : triggers.entrySet()) {
      var recall = id();
      assertStatus(
          api.post(
              tenant.id(),
              "/recalls",
              recall,
              Map.of(
                  "id",
                  recall,
                  "triggerType",
                  trigger.getKey(),
                  "triggerId",
                  trigger.getValue(),
                  "reason",
                  "Investigate source evidence")),
          201);
      var impact =
          api.post(
              tenant.id(),
              "/recalls/" + recall + ":analyze-impact",
              Map.of("page", 0, "size", 100));
      assertStatus(impact, 200);
      var rows = json.readTree(impact.body()).path("materials");
      assertThat(rows.size())
          .as(trigger.getKey())
          .isEqualTo(
              Set.of("PACKAGE", "CRYOPRESERVATION_EVENT").contains(trigger.getKey()) ? 1 : 2);
      boolean foundDestination = false;
      for (var row : rows) {
        assertThat(row.path("donorId").asString()).isEqualTo(production.donor().toString());
        assertThat(row.path("semenBatchId").asString())
            .isEqualTo(production.semenBatch().toString());
        assertThat(row.path("sireId").asString()).isEqualTo(production.sire().toString());
        assertThat(row.path("matingId").asString()).isEqualTo(production.mating().toString());
        if (row.path("shipmentId").asString().equals(shipment.toString())) {
          foundDestination = true;
          assertThat(row.path("destinationName").asString()).isEqualTo("Destination Lab");
          assertThat(row.path("currentLocation").isNull()).isTrue();
          assertThat(row.path("availability").asString()).isEqualTo("SHIPPED_OUT");
        }
      }
      assertThat(foundDestination).as(trigger.getKey()).isTrue();
      if (trigger.getKey().equals("DONOR")) donorRecall = recall;
    }
    var returned = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":return",
            returned,
            Map.of(
                "movementId",
                returned,
                "itemId",
                item.get("id"),
                "locationId",
                shipped.location(),
                "expectedShipmentVersion",
                1,
                "expectedPackageVersion",
                4,
                "occurredAt",
                Instant.parse("2026-09-03T12:00:00Z"),
                "reason",
                "Observed return")),
        200);
    var afterReturn =
        api.post(
            tenant.id(),
            "/recalls/" + donorRecall + ":analyze-impact",
            Map.of("page", 0, "size", 100));
    assertStatus(afterReturn, 200);
    assertThat(afterReturn.body())
        .contains(
            shipment.toString(),
            "Destination Lab",
            shipped.location().toString(),
            stored.location().toString());
  }

  @Test
  void lineageImpactAlsoIncludesFreshPerformedTransfersWithoutPackages() throws Exception {
    var tenant = tenant("Fresh Recall Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient A");
    var cycle =
        TransferFixtures.openCycle(api, tenant.id(), recipient, java.time.LocalDate.of(2026, 9, 1));
    var reservation =
        TransferFixtures.reservation(api, tenant.id(), production.embryos().getFirst(), cycle, 0);
    var transfer =
        TransferFixtures.perform(
            api,
            tenant.id(),
            reservation.id(),
            1,
            production.professional(),
            DistributionFixtures.DISPATCHED_AT);
    var recall = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/recalls",
            recall,
            Map.of(
                "id",
                recall,
                "triggerType",
                "SEMEN_BATCH",
                "triggerId",
                production.semenBatch(),
                "reason",
                "Review semen source")),
        201);
    var impact =
        api.post(
            tenant.id(), "/recalls/" + recall + ":analyze-impact", Map.of("page", 0, "size", 20));
    assertStatus(impact, 200);
    var row = json.readTree(impact.body()).path("materials").get(0);
    assertThat(row.path("transferId").asString()).isEqualTo(transfer.id().toString());
    assertThat(row.path("packageId").isNull()).isTrue();
    assertThat(row.path("availability").asString()).isEqualTo("TRANSFERRED");
  }
}
