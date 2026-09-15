package com.bovina.opu;

import static com.bovina.support.fixture.OpuFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OocyteCollectionIdempotencyIT extends OpuIntegrationTest {
  @Test
  void concurrentDeliveryCreatesOneCollectionAndOneAuditEvent() throws Exception {
    var opu = startedSession(api, tenant("Idempotent OPU Lab"));
    var collection = id();
    var command =
        collectionBatch(collection(collection, donor(api, opu.tenant(), "Donor A"), 6, 4));
    var start = new CyclicBarrier(3);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 3; index++) {
        requests.add(
            executor.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command);
                }));
      }
      JsonNode canonical = null;
      for (var request : requests) {
        var response = request.get(20, TimeUnit.SECONDS);
        assertStatus(response, 200);
        if (canonical == null) {
          canonical = json.readTree(response.body());
        } else {
          assertThat(json.readTree(response.body())).isEqualTo(canonical);
        }
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE id=?", Integer.class, collection))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='RECORD'",
                Integer.class,
                collection))
        .isEqualTo(1);
    var changed = new HashMap<String, Object>(command);
    changed.put(
        "items", List.of(collection(collection, donor(api, opu.tenant(), "Donor B"), 5, 4)));
    assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", changed), 409);
  }

  @Test
  void databaseConflictRollsBackTheBatchAndAllowsCorrectedRetry() throws Exception {
    var opu = startedSession(api, tenant("Retry OPU Lab"));
    var other = startedSession(api, tenant("Other OPU Lab"));
    var collidingId = id();
    assertStatus(
        api.post(
            other.tenant().id(),
            other.path() + "/collections:bulk",
            collectionBatch(
                collection(collidingId, donor(api, other.tenant(), "Other Donor"), 1, 1))),
        200);
    var validId = id();
    var command =
        collectionBatch(
            List.of(
                collection(validId, donor(api, opu.tenant(), "Donor A"), 3, 2),
                collection(collidingId, donor(api, opu.tenant(), "Donor B"), 3, 2)));

    assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command), 409);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE id=?", Integer.class, validId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotent_command WHERE organization_id=? AND idempotency_key=?",
                Integer.class,
                opu.tenant().id(),
                command.get("batchId")))
        .isZero();
    var corrected = new HashMap<String, Object>(command);
    corrected.put(
        "items", List.of(collection(validId, donor(api, opu.tenant(), "Replacement Donor"), 3, 2)));
    assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", corrected), 200);
  }
}
