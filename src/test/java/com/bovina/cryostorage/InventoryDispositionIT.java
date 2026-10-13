package com.bovina.cryostorage;

import static com.bovina.support.fixture.CryostorageFixtures.physicalMovement;
import static com.bovina.support.fixture.CryostorageFixtures.storageLocation;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryDispositionIT extends AuthenticatedIntegrationTest {
  @Test
  void physicalDisposalAcceptsPreviouslyDiscardedMemberButStillRespectsHolds() throws Exception {
    var tenant = tenant("Mixed Disposition Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var ids = new com.bovina.platform.application.StableIds();
    var first =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().get(0), production, ids);
    var second =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().get(1), production, ids);
    var packageId = id();
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
                "MIXED-DISPOSE-" + packageId,
                "packagingType",
                "CONTAINER",
                "packagedAt",
                Instant.parse("2026-09-06T07:00:00Z"))),
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
                    Map.of("id", id(), "cryopreservationItemId", first.cryoItem()),
                    Map.of("id", id(), "cryopreservationItemId", second.cryoItem())))),
        200);
    assertStatus(
        api.post(
            tenant.id(), "/embryo-packages/" + packageId + ":seal", Map.of("expectedVersion", 1)),
        200);
    var location =
        storageLocation(
            api,
            tenant.id(),
            production.inputs().opu().establishment(),
            "MIXED_DISPOSAL_RACK",
            ids);
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
                location,
                2,
                Instant.parse("2026-09-06T08:00:00Z"),
                null)),
        201);

    assertStatus(
        api.post(
            tenant.id(),
            "/embryos/" + first.embryoId() + ":discard",
            Map.of("expectedVersion", 0, "reason", "Decision to discard")),
        200);
    var hold = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryos/" + second.embryoId() + "/holds",
            Map.of("id", hold, "type", "QUALITY", "reason", "Review pending")),
        200);
    var dispose = id();
    var command =
        physicalMovement(
            dispose,
            packageId,
            "DISPOSE",
            location,
            null,
            3,
            Instant.parse("2026-09-06T10:00:00Z"),
            "Physical destruction witnessed");
    assertStatus(api.post(tenant.id(), "/inventory-movements", dispose, command), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=? AND movement_type='DISPOSE'",
                Integer.class,
                tenant.id(),
                packageId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                java.util.UUID.class,
                tenant.id(),
                packageId))
        .isEqualTo(location);

    assertStatus(
        api.post(
            tenant.id(),
            "/embryos/" + second.embryoId() + "/holds/" + hold + ":release",
            Map.of("reason", "Review complete")),
        200);
    assertStatus(api.post(tenant.id(), "/inventory-movements", dispose, command), 201);
    assertStatus(api.post(tenant.id(), "/inventory-movements", dispose, command), 201);
    assertThat(
            jdbc.queryForList(
                "SELECT availability_status FROM embryo WHERE organization_id=? AND id IN (?,?) ORDER BY id",
                String.class,
                tenant.id(),
                first.embryoId(),
                second.embryoId()))
        .containsExactly("DISCARDED", "DISCARDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=? AND movement_type='DISPOSE'",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);
    assertThat(
            api.get(tenant.id(), "/inventory-movements/packages/" + packageId + "/projection-check")
                .body())
        .contains("\"matchesLedger\":true");
  }

  @Test
  void disposalRemovesPhysicalPackageAndDiscardsEmbryoInTheSameTransaction() throws Exception {
    var tenant = tenant("Disposal Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packageId = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids).packageId();
    var location =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "DISPOSAL_RACK", ids);
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
                location,
                2,
                Instant.parse("2026-09-06T08:00:00Z"),
                null)),
        201);
    var dispose = id();
    var command =
        physicalMovement(
            dispose,
            packageId,
            "DISPOSE",
            location,
            null,
            3,
            Instant.parse("2026-09-06T10:00:00Z"),
            "Authorized disposal after review");
    assertStatus(api.post(tenant.id(), "/inventory-movements", dispose, command), 201);
    assertStatus(api.post(tenant.id(), "/inventory-movements", dispose, command), 201);
    assertThat(
            jdbc.queryForMap(
                "SELECT p.status,p.current_location_id,e.availability_status FROM embryo_package p JOIN package_item i ON i.organization_id=p.organization_id AND i.package_id=p.id JOIN embryo e ON e.organization_id=i.organization_id AND e.id=i.embryo_id WHERE p.organization_id=? AND p.id=?",
                tenant.id(),
                packageId))
        .containsEntry("status", "DISPOSED")
        .containsEntry("availability_status", "DISCARDED")
        .containsEntry("current_location_id", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=? AND movement_type='DISPOSE'",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);
  }
}
