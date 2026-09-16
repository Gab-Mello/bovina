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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryBulkIT extends AuthenticatedIntegrationTest {
  @Test
  void movementsForOnePackageAreAtomicOrderedAndIdempotent() throws Exception {
    var tenant = tenant("Bulk Scan Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packageId = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids).packageId();
    var a =
        storageLocation(api, tenant.id(), production.inputs().opu().establishment(), "BULK_A", ids);
    var b =
        storageLocation(api, tenant.id(), production.inputs().opu().establishment(), "BULK_B", ids);
    var failedBatch = id();
    var invalid =
        Map.of(
            "batchId",
            failedBatch,
            "items",
            List.of(
                physicalMovement(
                    id(),
                    packageId,
                    "RECEIVE",
                    null,
                    a,
                    2,
                    Instant.parse("2026-09-05T10:00:00Z"),
                    null),
                physicalMovement(
                    id(),
                    packageId,
                    "MOVE",
                    a,
                    b,
                    2,
                    Instant.parse("2026-09-05T10:00:00Z"),
                    null)));
    assertStatus(api.post(tenant.id(), "/inventory-movements/bulk", failedBatch, invalid), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                packageId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                UUID.class,
                tenant.id(),
                packageId))
        .isNull();

    var batchId = id();
    var valid =
        Map.of(
            "batchId",
            batchId,
            "items",
            List.of(
                physicalMovement(
                    id(),
                    packageId,
                    "RECEIVE",
                    null,
                    a,
                    2,
                    Instant.parse("2026-09-05T10:00:00Z"),
                    null),
                physicalMovement(
                    id(),
                    packageId,
                    "MOVE",
                    a,
                    b,
                    3,
                    Instant.parse("2026-09-05T10:00:00Z"),
                    null)));
    assertStatus(api.post(tenant.id(), "/inventory-movements/bulk", batchId, valid), 200);
    assertStatus(api.post(tenant.id(), "/inventory-movements/bulk", batchId, valid), 200);
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
        .isEqualTo(b);
  }
}
