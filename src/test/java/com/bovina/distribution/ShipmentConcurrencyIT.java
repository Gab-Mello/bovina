package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.support.fixture.*;
import com.bovina.support.integration.OperationalDistributionTest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ShipmentConcurrencyIT extends OperationalDistributionTest {
  @Test
  void competingShipmentsLockSharedPackagesInOrderAndCommitOneReservationSet() throws Exception {
    var tenant = tenant("Reservation Collision Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var stock =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(0), "Rack A");
    var otherStock =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(1), "Rack B");
    var recipient = DistributionFixtures.recipient(api, tenant);
    var first = id();
    var second = id();
    var results =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments",
                    first,
                    DistributionFixtures.preparation(
                        first,
                        recipient,
                        stock,
                        List.of(
                            DistributionFixtures.item(stock),
                            DistributionFixtures.item(otherStock)),
                        List.of())),
            () ->
                api.post(
                    tenant.id(),
                    "/shipments",
                    second,
                    DistributionFixtures.preparation(
                        second,
                        recipient,
                        stock,
                        List.of(
                            DistributionFixtures.item(otherStock),
                            DistributionFixtures.item(stock)),
                        List.of())));
    assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_shipment_reservation WHERE organization_id=? AND released_at IS NULL",
                Integer.class,
                tenant.id()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM shipment WHERE organization_id=?",
                Integer.class,
                tenant.id()))
        .isEqualTo(1);
  }

  @Test
  void dispatchSerializesWithOrdinaryMoveAndRetryAppendsOnePhysicalFact() throws Exception {
    var tenant = tenant("Movement Collision Lab");
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
    var destination =
        CryostorageFixtures.storageLocation(
            api,
            tenant.id(),
            stock.production().inputs().opu().establishment(),
            "Other rack",
            new com.bovina.platform.application.StableIds());
    var dispatchKey = id();
    var move = id();
    var results =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    dispatchKey,
                    DistributionFixtures.dispatch()),
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-movements",
                    move,
                    CryostorageFixtures.physicalMovement(
                        move,
                        stock.packaged().packageId(),
                        "MOVE",
                        stock.location(),
                        destination,
                        3,
                        DistributionFixtures.DISPATCHED_AT,
                        null)));
    assertThat(results.get(0).statusCode()).as(results.get(0).body()).isEqualTo(200);
    assertThat(results.get(1).statusCode()).as(results.get(1).body()).isEqualTo(409);
    var retries =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    dispatchKey,
                    DistributionFixtures.dispatch()),
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    dispatchKey,
                    DistributionFixtures.dispatch()));
    assertThat(retries).extracting(HttpResponse::statusCode).containsOnly(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND shipment_item_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id(),
                item.get("id")))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=? AND action='DISPATCH'",
                Integer.class,
                tenant.id(),
                shipment))
        .isEqualTo(1);
    assertThat(
            api.get(
                    tenant.id(),
                    "/inventory-movements/packages/"
                        + stock.packaged().packageId()
                        + "/projection-check")
                .body())
        .contains("\"matchesLedger\":true");
  }

  @Test
  void holdAndDispatchRaceCannotLeaveHeldMaterialDispatched() throws Exception {
    var tenant = tenant("Hold Collision Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    var hold = id();
    var results =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    DistributionFixtures.dispatch()),
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-holds",
                    hold,
                    Map.of(
                        "id",
                        hold,
                        "packageId",
                        stock.packaged().packageId(),
                        "typeCode",
                        "INCIDENT",
                        "reason",
                        "Observed incident")));
    assertThat(results).extracting(HttpResponse::statusCode).contains(409);
    var shipmentStatus =
        json.readTree(api.get(tenant.id(), "/shipments/" + shipment).body())
            .path("status")
            .asString();
    var shipCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
            Integer.class,
            tenant.id());
    var holdCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM inventory_hold WHERE organization_id=? AND released_at IS NULL",
            Integer.class,
            tenant.id());
    if (shipmentStatus.equals("SHIPPED")) {
      assertThat(shipCount).isEqualTo(1);
      assertThat(holdCount).isZero();
      assertThat(results.get(0).statusCode()).isEqualTo(200);
    } else {
      assertThat(shipCount).isZero();
      assertThat(holdCount).isEqualTo(1);
      assertThat(results.get(1).statusCode()).isEqualTo(201);
    }
  }

  @Test
  void cancellationAndDispatchRaceCommitExactlyOneTerminalOutcome() throws Exception {
    var tenant = tenant("Cancel Collision Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    var results =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    DistributionFixtures.dispatch()),
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":cancel",
                    Map.of("expectedVersion", 0, "reason", "Cancelled operation")));
    assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    var status =
        json.readTree(api.get(tenant.id(), "/shipments/" + shipment).body())
            .path("status")
            .asString();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id()))
        .isEqualTo(status.equals("SHIPPED") ? 1 : 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_shipment_reservation WHERE organization_id=? AND released_at IS NULL",
                Integer.class,
                tenant.id()))
        .isZero();
  }

  @Test
  void simultaneousDispatchRetriesCommitOneSnapshotAndOneMovementSet() throws Exception {
    var tenant = tenant("Dispatch Retry Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    var key = id();
    var results =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    key,
                    DistributionFixtures.dispatch()),
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    key,
                    DistributionFixtures.dispatch()));
    assertThat(results).extracting(HttpResponse::statusCode).containsOnly(200);
    assertThat(json.readTree(results.get(0).body()))
        .isEqualTo(json.readTree(results.get(1).body()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM shipment_destination_snapshot WHERE organization_id=? AND shipment_id=?",
                Integer.class,
                tenant.id(),
                shipment))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=? AND action='DISPATCH'",
                Integer.class,
                tenant.id(),
                shipment))
        .isEqualTo(1);
  }

  @Test
  void reservedPackageCannotBeWithdrawnOrThawedThroughDispatchRace() throws Exception {
    var tenant = tenant("Withdrawal Collision Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    var withdraw = id();
    var results =
        race(
            () ->
                api.post(
                    tenant.id(),
                    "/shipments/" + shipment + ":dispatch",
                    DistributionFixtures.dispatch()),
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-movements",
                    withdraw,
                    CryostorageFixtures.physicalMovement(
                        withdraw,
                        stock.packaged().packageId(),
                        "WITHDRAW",
                        stock.location(),
                        null,
                        3,
                        DistributionFixtures.DISPATCHED_AT,
                        null)));
    assertThat(results.get(0).statusCode()).as(results.get(0).body()).isEqualTo(200);
    assertThat(results.get(1).statusCode()).as(results.get(1).body()).isEqualTo(409);
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
                stock.packaged().packageId(),
                "professionalId",
                stock.production().professional(),
                "occurredAt",
                DistributionFixtures.DISPATCHED_AT,
                "resultCode",
                "OBSERVED",
                "packageItemIds",
                List.of(stock.packaged().packageItem()))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='WITHDRAW'",
                Integer.class,
                tenant.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM thaw_event WHERE organization_id=?",
                Integer.class,
                tenant.id()))
        .isZero();
  }

  private List<HttpResponse<String>> race(
      Callable<HttpResponse<String>> a, Callable<HttpResponse<String>> b) throws Exception {
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return a.call();
              });
      var second =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return b.call();
              });
      return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    }
  }
}
