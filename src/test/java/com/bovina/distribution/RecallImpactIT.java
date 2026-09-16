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

  @Test
  void materialThawedBeforeDispatchIsNotAttributedToTheRemainingPackagesDestination()
      throws Exception {
    var tenant = tenant("Partial Package Recall Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var ids = new com.bovina.platform.application.StableIds();
    var thawed =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().get(0), production, ids);
    var remaining =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().get(1), production, ids);
    var packageId = id();
    var thawedMember = id();
    var remainingMember = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryo-packages",
            packageId,
            Map.of(
                "id",
                packageId,
                "establishmentId",
                production.inputs().opu().establishment(),
                "packageCode",
                "Partial package",
                "packagingType",
                "CONTAINER",
                "packagedAt",
                DistributionFixtures.STORED_AT)),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            "/embryo-packages/" + packageId + "/items:bulk",
            Map.of(
                "expectedVersion",
                0,
                "items",
                List.of(
                    Map.of("id", thawedMember, "cryopreservationItemId", thawed.cryoItem()),
                    Map.of(
                        "id", remainingMember, "cryopreservationItemId", remaining.cryoItem())))),
        200);
    assertStatus(
        api.post(
            tenant.id(), "/embryo-packages/" + packageId + ":seal", Map.of("expectedVersion", 1)),
        200);
    var location =
        CryostorageFixtures.storageLocation(
            api,
            tenant.id(),
            production.inputs().opu().establishment(),
            "Partial package rack",
            ids);
    var receive = id();
    var withdraw = id();
    var restore = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            receive,
            CryostorageFixtures.physicalMovement(
                receive,
                packageId,
                "STORE",
                null,
                location,
                2,
                DistributionFixtures.STORED_AT,
                null)),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            withdraw,
            CryostorageFixtures.physicalMovement(
                withdraw,
                packageId,
                "WITHDRAW",
                location,
                null,
                3,
                DistributionFixtures.DISPATCHED_AT,
                null)),
        201);
    var thaw = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/thaw-events",
            thaw,
            Map.of(
                "id",
                thaw,
                "packageId",
                packageId,
                "occurredAt",
                DistributionFixtures.DISPATCHED_AT,
                "professionalId",
                production.professional(),
                "resultCode",
                "OBSERVED",
                "packageItemIds",
                List.of(thawedMember))),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            restore,
            CryostorageFixtures.physicalMovement(
                restore,
                packageId,
                "STORE",
                null,
                location,
                5,
                DistributionFixtures.DISPATCHED_AT,
                null)),
        201);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            Map.of(
                "id",
                shipment,
                "establishmentId",
                production.inputs().opu().establishment(),
                "recipientId",
                recipient,
                "propertyId",
                production.inputs().opu().property(),
                "purpose",
                "OPERATIONAL_CUSTODY",
                "items",
                List.of(
                    Map.of(
                        "id",
                        id(),
                        "packageId",
                        packageId,
                        "expectedVersion",
                        6,
                        "expectedLocationId",
                        location)))),
        201);
    assertStatus(
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch()),
        200);
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
                "Review material destinations")),
        201);
    var impact =
        api.post(
            tenant.id(), "/recalls/" + recall + ":analyze-impact", Map.of("page", 0, "size", 100));
    assertStatus(impact, 200);
    var rows = json.readTree(impact.body()).path("materials");
    assertThat(rows.size()).isEqualTo(2);
    for (var row : rows) {
      if (row.path("embryoId").asString().equals(thawed.embryoId().toString())) {
        assertThat(row.path("activeMembership").asBoolean()).isFalse();
        assertThat(row.path("shipmentId").isNull()).isTrue();
        assertThat(row.path("destinationName").isNull()).isTrue();
      } else {
        assertThat(row.path("embryoId").asString()).isEqualTo(remaining.embryoId().toString());
        assertThat(row.path("shipmentId").asString()).isEqualTo(shipment.toString());
      }
    }
    var removedMaterialRecall = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/recalls",
            removedMaterialRecall,
            Map.of(
                "id",
                removedMaterialRecall,
                "triggerType",
                "CRYOPRESERVATION_EVENT",
                "triggerId",
                thawed.cryoEvent(),
                "reason",
                "Review removed material")),
        201);
    var removedImpact =
        api.post(
            tenant.id(),
            "/recalls/" + removedMaterialRecall + ":analyze-impact",
            Map.of("page", 0, "size", 100));
    assertStatus(removedImpact, 200);
    var execution = id();
    var result =
        api.post(
            tenant.id(),
            "/recalls/" + removedMaterialRecall + ":place-holds",
            execution,
            Map.of(
                "id",
                execution,
                "cutoff",
                json.readTree(removedImpact.body()).path("analysisCutoff").asString(),
                "items",
                List.of(Map.of("id", id(), "packageId", packageId, "expectedVersion", 7))));
    assertStatus(result, 200);
    assertThat(result.body()).contains("MATERIAL_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_hold WHERE organization_id=?",
                Integer.class,
                tenant.id()))
        .isZero();
  }
}
