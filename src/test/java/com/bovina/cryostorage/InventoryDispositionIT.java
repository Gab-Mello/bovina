package com.bovina.cryostorage;

import static com.bovina.support.fixture.CryostorageFixtures.physicalMovement;
import static com.bovina.support.fixture.CryostorageFixtures.storageLocation;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InventoryDispositionIT extends AuthenticatedIntegrationTest {
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
