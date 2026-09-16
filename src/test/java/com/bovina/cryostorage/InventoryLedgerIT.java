package com.bovina.cryostorage;

import static com.bovina.support.fixture.CryostorageFixtures.physicalMovement;
import static com.bovina.support.fixture.CryostorageFixtures.storageLocation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class InventoryLedgerIT extends AuthenticatedIntegrationTest {
  @Test
  void physicalLedgerAloneRebuildsLocationAndHoldsDoNotChangeIt() throws Exception {
    var tenant = tenant("Inventory Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packageId = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids).packageId();
    var first =
        storageLocation(api, tenant.id(), production.inputs().opu().establishment(), "RACK_A", ids);
    var second =
        storageLocation(api, tenant.id(), production.inputs().opu().establishment(), "RACK_B", ids);

    var receiveId = id();
    var receive =
        physicalMovement(
            receiveId,
            packageId,
            "RECEIVE",
            null,
            first,
            2,
            java.time.Instant.parse("2026-09-02T10:00:00Z"),
            null);
    assertStatus(api.post(tenant.id(), "/inventory-movements", receiveId, receive), 201);
    assertStatus(api.post(tenant.id(), "/inventory-movements", receiveId, receive), 201);
    var changed = new HashMap<>(receive);
    changed.put("destinationId", second);
    assertStatus(api.post(tenant.id(), "/inventory-movements", receiveId, changed), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);

    var moveId = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            moveId,
            physicalMovement(
                moveId,
                packageId,
                "MOVE",
                first,
                second,
                3,
                java.time.Instant.parse("2026-09-02T10:00:00Z"),
                null)),
        201);
    var holdId = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-holds",
            holdId,
            Map.of(
                "id",
                holdId,
                "packageId",
                packageId,
                "typeCode",
                "QUALITY_REVIEW",
                "reason",
                "Pending observed quality review")),
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                UUID.class,
                tenant.id(),
                packageId))
        .isEqualTo(second);
    var blockedId = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            blockedId,
            physicalMovement(
                blockedId,
                packageId,
                "WITHDRAW",
                second,
                null,
                4,
                java.time.Instant.parse("2026-09-02T10:00:00Z"),
                null)),
        409);
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-holds/" + holdId + ":release",
            Map.of("reason", "Review completed")),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_hold_event WHERE organization_id=? AND hold_id=?",
                Integer.class,
                tenant.id(),
                holdId))
        .isEqualTo(2);

    var withdrawId = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            withdrawId,
            physicalMovement(
                withdrawId,
                packageId,
                "WITHDRAW",
                second,
                null,
                4,
                java.time.Instant.parse("2026-09-02T10:00:00Z"),
                null)),
        201);
    var projection =
        api.get(tenant.id(), "/inventory-movements/packages/" + packageId + "/projection-check");
    assertStatus(projection, 200);
    assertThat(projection.body()).contains("\"matchesLedger\":true");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                UUID.class,
                tenant.id(),
                packageId))
        .isNull();
    assertThatThrownBy(
            () ->
                jdbc.update("UPDATE inventory_movement SET reason='rewrite' WHERE id=?", receiveId))
        .isInstanceOf(DataAccessException.class);
  }
}
