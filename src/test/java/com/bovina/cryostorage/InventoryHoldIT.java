package com.bovina.cryostorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class InventoryHoldIT extends AuthenticatedIntegrationTest {
  @Test
  void competingHoldsCreateOneActiveRestrictionWithoutAnyPhysicalMovement() throws Exception {
    var tenant = tenant("Custody Review Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packageId = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids).packageId();
    var first = id();
    var second = id();
    var race =
        concurrently(
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-holds",
                    first,
                    Map.of(
                        "id",
                        first,
                        "packageId",
                        packageId,
                        "typeCode",
                        "CUSTODY_REVIEW",
                        "reason",
                        "First quality concern")),
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-holds",
                    second,
                    Map.of(
                        "id",
                        second,
                        "packageId",
                        packageId,
                        "typeCode",
                        "CUSTODY_REVIEW",
                        "reason",
                        "Second quality concern")));
    assertThat(race).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_hold WHERE organization_id=? AND package_id=? AND released_at IS NULL",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                Integer.class,
                tenant.id(),
                packageId))
        .isZero();
    var winner = race.getFirst().statusCode() == 201 ? first : second;
    var releaseKey = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-holds/" + winner + ":release",
            releaseKey,
            Map.of("reason", "Custody review completed")),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-holds/" + winner + ":release",
            releaseKey,
            Map.of("reason", "Custody review completed")),
        200);
    var replacement = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-holds",
            replacement,
            Map.of(
                "id",
                replacement,
                "packageId",
                packageId,
                "typeCode",
                "CUSTODY_REVIEW",
                "reason",
                "New independent concern")),
        201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_hold_event WHERE organization_id=? AND hold_id=?",
                Integer.class,
                tenant.id(),
                winner))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE inventory_hold_event SET reason='rewrite' WHERE organization_id=? AND hold_id=?",
                    tenant.id(),
                    winner))
        .isInstanceOf(DataAccessException.class);
  }

  private List<HttpResponse<String>> concurrently(
      Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
      throws Exception {
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var one =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return first.call();
              });
      var two =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
    }
  }
}
