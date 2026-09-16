package com.bovina.fertilization;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.fixture.ProductionFixtures.FertilizationInputs;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatingLineageIT extends AuthenticatedIntegrationTest {
  @Test
  void multiMatingPreservesDonorSireAndSemenBatchLineageWithinTheTenant() throws Exception {
    var inputs = ProductionFixtures.fertilizationInputs(api, tenant("Lineage Lab"), 5);
    var firstMating = id();
    var secondMating = id();
    var batchId = id();
    var command =
        batch(batchId, List.of(item(firstMating, inputs, 2), item(secondMating, inputs, 3)));

    var created = api.post(inputs.tenant().id(), "/matings:bulk", batchId, command);

    assertStatus(created, 200);
    assertThat(
            json.readTree(api.post(inputs.tenant().id(), "/matings:bulk", batchId, command).body()))
        .isEqualTo(json.readTree(created.body()));
    assertStatus(
        api.post(
            inputs.tenant().id(),
            "/matings:bulk",
            batchId,
            batch(batchId, List.of(item(id(), inputs, 1)))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(allocated_oocytes) FROM mating WHERE oocyte_collection_id=?",
                Integer.class,
                inputs.collection()))
        .isEqualTo(5);
    assertThat(api.get(inputs.tenant().id(), "/matings/" + firstMating).body())
        .contains(
            inputs.collection().toString(),
            inputs.semenBatch().toString(),
            inputs.donor().toString(),
            inputs.sire().toString());
    assertThat(api.get(inputs.tenant().id(), "/matings?collectionId=" + inputs.collection()).body())
        .contains(firstMating.toString(), secondMating.toString());
    assertThat(api.get(inputs.tenant().id(), "/matings?semenBatchId=" + inputs.semenBatch()).body())
        .contains(firstMating.toString(), secondMating.toString());

    assertStatus(
        api.post(
            inputs.tenant().id(),
            "/matings/" + firstMating + "/corrections",
            Map.of("reason", "Supplier document correction requested")),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM record_correction WHERE subject_id=?",
                String.class,
                firstMating))
        .isEqualTo("REQUESTED");

    var foreign = ProductionFixtures.fertilizationInputs(api, tenant("Foreign Lab"), 1);
    assertStatus(api.get(foreign.tenant().id(), "/matings/" + firstMating), 404);
    assertStatus(api.get(foreign.tenant().id(), "/semen-batches/" + inputs.semenBatch()), 404);
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
