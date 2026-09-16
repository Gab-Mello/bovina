package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.support.fixture.*;
import com.bovina.support.integration.OperationalDistributionTest;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShipmentAtomicDispatchIT extends OperationalDistributionTest {
  @Test
  void heldEmbryoInOneItemRollsBackEntireShipmentAndRetryCanCompleteAfterRelease()
      throws Exception {
    var tenant = tenant("Atomic Dispatch Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var first =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(0), "Rack A");
    var second =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(1), "Rack B");
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment,
                recipient,
                first,
                List.of(DistributionFixtures.item(second), DistributionFixtures.item(first)),
                List.of())),
        201);
    var heldEmbryo = production.embryos().stream().max(Comparator.naturalOrder()).orElseThrow();
    var hold = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryos/" + heldEmbryo + "/holds",
            Map.of("id", hold, "type", "INCIDENT", "reason", "Review lineage evidence")),
        200);
    var key = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":dispatch",
            key,
            DistributionFixtures.dispatch()),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM shipment_destination_snapshot WHERE organization_id=?",
                Integer.class,
                tenant.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo WHERE organization_id=? AND availability_status='AVAILABLE'",
                Integer.class,
                tenant.id()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_shipment_reservation WHERE organization_id=? AND released_at IS NULL",
                Integer.class,
                tenant.id()))
        .isEqualTo(2);
    assertThat(
            json.readTree(api.get(tenant.id(), "/shipments/" + shipment).body())
                .path("status")
                .asString())
        .isEqualTo("DRAFT");
    assertStatus(
        api.post(
            tenant.id(),
            "/embryos/" + heldEmbryo + "/holds/" + hold + ":release",
            Map.of("reason", "Evidence reviewed")),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":dispatch",
            key,
            DistributionFixtures.dispatch()),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id()))
        .isEqualTo(2);
    for (var stock : List.of(first, second))
      assertThat(
              api.get(
                      tenant.id(),
                      "/inventory-movements/packages/"
                          + stock.packaged().packageId()
                          + "/projection-check")
                  .body())
          .contains("\"matchesLedger\":true");
  }
}
