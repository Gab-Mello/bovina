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

class InventoryReconciliationIT extends AuthenticatedIntegrationTest {
  @Test
  void observedDifferenceDoesNotChangeLedgerUntilAnAuditedAdjustmentIsRequested() throws Exception {
    var tenant = tenant("Stocktake Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packageId = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids).packageId();
    var a =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "EXPECTED_RACK", ids);
    var b =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "OBSERVED_RACK", ids);
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
                a,
                2,
                Instant.parse("2026-09-03T10:00:00Z"),
                null)),
        201);

    var reconciliation = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-reconciliations",
            reconciliation,
            Map.of(
                "id",
                reconciliation,
                "establishmentId",
                production.inputs().opu().establishment(),
                "scopeLocationId",
                a,
                "method",
                "SCAN")),
        201);
    var batch = id();
    var observed = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-reconciliations/" + reconciliation + "/observations:bulk",
            batch,
            Map.of(
                "batchId",
                batch,
                "observations",
                List.of(
                    Map.of(
                        "id",
                        observed,
                        "packageId",
                        packageId,
                        "observedLocationId",
                        b,
                        "observedAt",
                        Instant.parse("2026-09-03T12:00:00Z"))))),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-reconciliations/" + reconciliation + ":close",
            Map.of("expectedVersion", 0)),
        200);
    var discrepancy =
        api.get(tenant.id(), "/inventory-reconciliations/" + reconciliation + "/discrepancies");
    assertStatus(discrepancy, 200);
    assertThat(discrepancy.body()).contains(packageId.toString(), a.toString(), b.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);

    var adjustment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-reconciliations/" + reconciliation + ":resolve",
            adjustment,
            physicalMovement(
                adjustment,
                packageId,
                "ADJUST",
                a,
                b,
                3,
                Instant.parse("2026-09-03T10:00:00Z"),
                "Observed package at other rack during physical stocktake")),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT reconciliation_id FROM inventory_movement WHERE organization_id=? AND id=?",
                UUID.class,
                tenant.id(),
                adjustment))
        .isEqualTo(reconciliation);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=?",
                Integer.class,
                tenant.id(),
                adjustment))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                UUID.class,
                tenant.id(),
                packageId))
        .isEqualTo(b);
  }
}
