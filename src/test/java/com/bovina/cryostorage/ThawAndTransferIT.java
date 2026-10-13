package com.bovina.cryostorage;

import static com.bovina.support.fixture.CryostorageFixtures.physicalMovement;
import static com.bovina.support.fixture.CryostorageFixtures.storageLocation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.fixture.TransferFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import com.bovina.support.integration.PostgresTestAssertions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ThawAndTransferIT extends AuthenticatedIntegrationTest {
  @Test
  void concurrentThawAndRepackagingCannotReuseTheConsumedPreservation() throws Exception {
    var tenant = tenant("Concurrent Thaw Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packaged = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids);
    var rack =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "RACE_RACK", ids);
    var receive = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            receive,
            physicalMovement(
                receive,
                packaged.packageId(),
                "RECEIVE",
                null,
                rack,
                2,
                Instant.parse("2026-09-04T09:00:00Z"),
                null)),
        201);
    var withdraw = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            withdraw,
            physicalMovement(
                withdraw,
                packaged.packageId(),
                "WITHDRAW",
                rack,
                null,
                3,
                Instant.parse("2026-09-04T10:00:00Z"),
                null)),
        201);
    var replacement = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryo-packages",
            replacement,
            Map.of(
                "id",
                replacement,
                "establishmentId",
                production.inputs().opu().establishment(),
                "packageCode",
                "RACE-" + replacement,
                "packagingType",
                "CONTAINER",
                "packagedAt",
                Instant.parse("2026-09-04T10:00:00Z"))),
        201);
    var thaw = id();
    var thawCommand =
        Map.<String, Object>of(
            "id",
            thaw,
            "packageId",
            packaged.packageId(),
            "occurredAt",
            Instant.parse("2026-09-04T11:00:00Z"),
            "professionalId",
            production.professional(),
            "resultCode",
            "OBSERVED_VIABLE",
            "packageItemIds",
            List.of(packaged.packageItem()));
    var addKey = id();
    var addCommand =
        Map.of(
            "expectedVersion",
            0,
            "items",
            List.of(Map.of("id", id(), "cryopreservationItemId", cryo.cryoItem())));

    try (var lock = TestDatabase.runtimeConnection()) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement(
              "SELECT id FROM embryo WHERE organization_id=? AND id=? FOR UPDATE")) {
        statement.setObject(1, tenant.id());
        statement.setObject(2, cryo.embryoId());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
      var start = new CyclicBarrier(2);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var thawRequest =
            executor.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return api.post(tenant.id(), "/thaw-events", thaw, thawCommand);
                });
        var addRequest =
            executor.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return api.post(
                      tenant.id(),
                      "/embryo-packages/" + replacement + "/items:bulk",
                      addKey,
                      addCommand);
                });
        PostgresTestAssertions.awaitLockWait("embryo", 2);
        lock.commit();
        assertStatus(thawRequest.get(30, TimeUnit.SECONDS), 201);
        assertStatus(addRequest.get(30, TimeUnit.SECONDS), 409);
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM package_item WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                replacement))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM thaw_event_item WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
  }

  @Test
  void thawedTransferRequiresWithdrawnItemLevelEvidenceAndPreservesLineage() throws Exception {
    var tenant = tenant("Thawed Transfer Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packaged = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids);
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient A");
    var cycle = TransferFixtures.openCycle(api, tenant.id(), recipient, LocalDate.of(2026, 8, 30));
    var freshBatch = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/transfers:bulk-reserve",
            freshBatch,
            TransferFixtures.reservationBatch(
                freshBatch, TransferFixtures.reservationItem(id(), cryo.embryoId(), cycle, 0))),
        409);

    var rack =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "THAW_RACK", ids);
    var receive = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            receive,
            physicalMovement(
                receive,
                packaged.packageId(),
                "RECEIVE",
                null,
                rack,
                2,
                Instant.parse("2026-09-04T09:00:00Z"),
                null)),
        201);
    var withdraw = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            withdraw,
            physicalMovement(
                withdraw,
                packaged.packageId(),
                "WITHDRAW",
                rack,
                null,
                3,
                Instant.parse("2026-09-04T09:00:00Z"),
                null)),
        201);
    var thawId = id();
    var thawCommand =
        Map.<String, Object>of(
            "id",
            thawId,
            "packageId",
            packaged.packageId(),
            "occurredAt",
            Instant.parse("2026-09-04T10:00:00Z"),
            "professionalId",
            production.professional(),
            "resultCode",
            "OBSERVED_VIABLE",
            "packageItemIds",
            List.of(packaged.packageItem()));
    assertStatus(api.post(tenant.id(), "/thaw-events", thawId, thawCommand), 201);
    assertStatus(api.post(tenant.id(), "/thaw-events", thawId, thawCommand), 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM thaw_event_item WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
    assertThat(api.get(tenant.id(), "/embryos/" + cryo.embryoId()).body())
        .contains("\"preservation\":\"THAWED\"");
    var replacementPackage = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryo-packages",
            replacementPackage,
            Map.of(
                "id",
                replacementPackage,
                "establishmentId",
                production.inputs().opu().establishment(),
                "packageCode",
                "REPACKAGE-" + replacementPackage,
                "packagingType",
                "CONTAINER",
                "packagedAt",
                Instant.parse("2026-09-04T11:00:00Z"))),
        201);
    var rejectedMembership = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryo-packages/" + replacementPackage + "/items:bulk",
            rejectedMembership,
            Map.of(
                "expectedVersion",
                0,
                "items",
                List.of(Map.of("id", id(), "cryopreservationItemId", cryo.cryoItem())))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM package_item WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                replacementPackage))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotent_command WHERE organization_id=? AND command_type='ADD_PACKAGE_MEMBERS_V1' AND idempotency_key=?",
                Integer.class,
                tenant.id(),
                rejectedMembership))
        .isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE thaw_event SET result_code='REWRITTEN' WHERE organization_id=? AND id=?",
                    tenant.id(),
                    thawId))
        .isInstanceOf(DataAccessException.class);

    var transferId = id();
    var transfer =
        Map.<String, Object>of(
            "transferId",
            transferId,
            "reservationId",
            id(),
            "embryoId",
            cryo.embryoId(),
            "recipientCycleId",
            cycle,
            "thawEventId",
            thawId,
            "expectedEmbryoVersion",
            0,
            "performedAt",
            Instant.parse("2026-09-04T12:00:00Z"),
            "timezone",
            "America/Sao_Paulo",
            "operatorProfessionalId",
            production.professional());
    assertStatus(api.post(tenant.id(), "/thawed-transfers:perform", transferId, transfer), 201);
    assertStatus(api.post(tenant.id(), "/thawed-transfers:perform", transferId, transfer), 201);
    var details = api.get(tenant.id(), "/transfers/" + transferId);
    assertStatus(details, 200);
    assertThat(details.body())
        .contains(
            "\"origin\":\"THAWED\"",
            thawId.toString(),
            production.donor().toString(),
            production.sire().toString(),
            production.semenBatch().toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
  }

  @Test
  void thawedItemCanTransferAfterPackageWithOtherMembersReturnsToStorage() throws Exception {
    var tenant = tenant("Multi-item Thaw Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var ids = new com.bovina.platform.application.StableIds();
    var first =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().get(0), production, ids);
    var second =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().get(1), production, ids);
    var packageId = id();
    var firstPackageItem = id();
    var secondPackageItem = id();
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
                "MULTI-" + packageId,
                "packagingType",
                "CONTAINER",
                "packagedAt",
                Instant.parse("2026-09-04T08:00:00Z"))),
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
                    Map.of("id", firstPackageItem, "cryopreservationItemId", first.cryoItem()),
                    Map.of("id", secondPackageItem, "cryopreservationItemId", second.cryoItem())))),
        200);
    assertStatus(
        api.post(
            tenant.id(), "/embryo-packages/" + packageId + ":seal", Map.of("expectedVersion", 1)),
        200);
    var rack =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "MULTI_RACK", ids);
    var receive = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            receive,
            physicalMovement(
                receive,
                packageId,
                "RECEIVE",
                null,
                rack,
                2,
                Instant.parse("2026-09-04T09:00:00Z"),
                null)),
        201);
    var withdrawal = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            withdrawal,
            physicalMovement(
                withdrawal,
                packageId,
                "WITHDRAW",
                rack,
                null,
                3,
                Instant.parse("2026-09-04T10:00:00Z"),
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
                Instant.parse("2026-09-04T11:00:00Z"),
                "professionalId",
                production.professional(),
                "resultCode",
                "OBSERVED_VIABLE",
                "packageItemIds",
                List.of(firstPackageItem))),
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT withdrawal_movement_id FROM thaw_event WHERE organization_id=? AND id=?",
                java.util.UUID.class,
                tenant.id(),
                thaw))
        .isEqualTo(withdrawal);
    var storeAgain = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            storeAgain,
            physicalMovement(
                storeAgain,
                packageId,
                "STORE",
                null,
                rack,
                5,
                Instant.parse("2026-09-04T12:00:00Z"),
                null)),
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                java.util.UUID.class,
                tenant.id(),
                packageId))
        .isEqualTo(rack);
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient B");
    var cycle = TransferFixtures.openCycle(api, tenant.id(), recipient, LocalDate.of(2026, 9, 1));
    var transfer = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/thawed-transfers:perform",
            transfer,
            Map.of(
                "transferId",
                transfer,
                "reservationId",
                id(),
                "embryoId",
                first.embryoId(),
                "recipientCycleId",
                cycle,
                "thawEventId",
                thaw,
                "expectedEmbryoVersion",
                0,
                "performedAt",
                Instant.parse("2026-09-04T13:00:00Z"),
                "timezone",
                "America/Sao_Paulo",
                "operatorProfessionalId",
                production.professional())),
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM package_item WHERE organization_id=? AND package_id=? AND removed_at IS NULL",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);
  }
}
