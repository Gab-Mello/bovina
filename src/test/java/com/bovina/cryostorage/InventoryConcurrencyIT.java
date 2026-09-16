package com.bovina.cryostorage;

import static com.bovina.support.fixture.CryostorageFixtures.physicalMovement;
import static com.bovina.support.fixture.CryostorageFixtures.storageLocation;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InventoryConcurrencyIT extends AuthenticatedIntegrationTest {
  @Test
  void competingMovesCommitExactlyOneNextSequenceAndProjectionMatchesIt() throws Exception {
    var tenant = tenant("Concurrent Storage Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packageId = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids).packageId();
    var first =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "START_RACK", ids);
    var second =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "TARGET_A", ids);
    var third =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "TARGET_B", ids);
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
                first,
                2,
                Instant.parse("2026-09-02T10:00:00Z"),
                null)),
        201);
    var a = id();
    var b = id();
    var results =
        concurrently(
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-movements",
                    a,
                    physicalMovement(
                        a,
                        packageId,
                        "MOVE",
                        first,
                        second,
                        3,
                        Instant.parse("2026-09-02T10:00:00Z"),
                        null)),
            () ->
                api.post(
                    tenant.id(),
                    "/inventory-movements",
                    b,
                    physicalMovement(
                        b,
                        packageId,
                        "MOVE",
                        first,
                        third,
                        3,
                        Instant.parse("2026-09-02T10:00:00Z"),
                        null)));

    assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=? AND sequence=2",
                Integer.class,
                tenant.id(),
                packageId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_movement_sequence FROM embryo_package WHERE organization_id=? AND id=?",
                Long.class,
                tenant.id(),
                packageId))
        .isEqualTo(2);
    var projection =
        api.get(tenant.id(), "/inventory-movements/packages/" + packageId + "/projection-check");
    assertStatus(projection, 200);
    assertThat(projection.body()).contains("\"matchesLedger\":true");
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
