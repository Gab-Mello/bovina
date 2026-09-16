package com.bovina.fertilization;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.fixture.ProductionFixtures.FertilizationInputs;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MatingAllocationConcurrencyIT extends AuthenticatedIntegrationTest {
  @Test
  void concurrentMatingsCommitOnlyWithinTheLockedCollectionBalance() throws Exception {
    var inputs = ProductionFixtures.fertilizationInputs(api, tenant("Allocation Lab"), 3);
    var start = new CyclicBarrier(2);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 2; index++) {
        var mating = id();
        var batchId = id();
        var command = batch(batchId, List.of(item(mating, inputs, 2)));
        requests.add(
            executor.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return api.post(inputs.tenant().id(), "/matings:bulk", batchId, command);
                }));
      }

      var statuses = new ArrayList<Integer>();
      for (var request : requests) {
        statuses.add(request.get(30, TimeUnit.SECONDS).statusCode());
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mating WHERE oocyte_collection_id=?",
                Integer.class,
                inputs.collection()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(allocated_oocytes) FROM mating WHERE oocyte_collection_id=?",
                Integer.class,
                inputs.collection()))
        .isEqualTo(2);
  }

  private Map<String, Object> batch(UUID batchId, List<Map<String, Object>> items) {
    return Map.of("batchId", batchId, "items", items);
  }

  private Map<String, Object> item(UUID mating, FertilizationInputs inputs, int allocation) {
    return Map.of(
        "itemId",
        id(),
        "id",
        mating,
        "collectionId",
        inputs.collection(),
        "semenBatchId",
        inputs.semenBatch(),
        "allocatedOocytes",
        allocation,
        "fertilizedAt",
        Instant.EPOCH,
        "method",
        "IVF",
        "responsibleProfessionalId",
        inputs.professional());
  }
}
