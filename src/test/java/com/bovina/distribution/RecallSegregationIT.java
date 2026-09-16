package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.support.fixture.*;
import com.bovina.support.integration.OperationalDistributionTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class RecallSegregationIT extends OperationalDistributionTest {
  @Test
  void explicitSegregationPersistsCutoffAndPerPackageConflictsWithoutMovingMaterial()
      throws Exception {
    var tenant = tenant("Recall Segregation Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var stored =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(0), "Stored rack");
    var shipped =
        DistributionFixtures.stockedEmbryo(
            api, tenant, production, production.embryos().get(1), "Shipped rack");
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
                shipped,
                List.of(DistributionFixtures.item(shipped)),
                List.of())),
        201);
    assertStatus(
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch()),
        200);
    var recall = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/recalls",
            recall,
            Map.of(
                "id",
                recall,
                "triggerType",
                "MATING",
                "triggerId",
                production.mating(),
                "reason",
                "Investigate recorded incident")),
        201);
    var impact =
        api.post(
            tenant.id(), "/recalls/" + recall + ":analyze-impact", Map.of("page", 0, "size", 100));
    assertStatus(impact, 200);
    var cutoff = json.readTree(impact.body()).path("analysisCutoff").asString();
    var execution = id();
    var hold = id();
    var input =
        Map.of(
            "id",
            execution,
            "cutoff",
            cutoff,
            "items",
            List.of(
                Map.of(
                    "id", hold, "packageId", stored.packaged().packageId(), "expectedVersion", 3),
                Map.of(
                    "id",
                    id(),
                    "packageId",
                    shipped.packaged().packageId(),
                    "expectedVersion",
                    4)));
    var segregated = api.post(tenant.id(), "/recalls/" + recall + ":place-holds", execution, input);
    assertStatus(segregated, 200);
    assertThat(segregated.body()).contains("HOLD_PLACED", "OUT_OF_CUSTODY", cutoff);
    var replay = api.post(tenant.id(), "/recalls/" + recall + ":place-holds", execution, input);
    assertStatus(replay, 200);
    assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(segregated.body()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_hold WHERE organization_id=? AND hold_type='RECALL'",
                Integer.class,
                tenant.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=?",
                Integer.class,
                tenant.id()))
        .isEqualTo(3);
    var persisted = api.get(tenant.id(), "/recalls/" + recall + "/hold-executions/" + execution);
    assertStatus(persisted, 200);
    assertThat(persisted.body()).contains("HOLD_PLACED", "OUT_OF_CUSTODY", cutoff);
    var other = tenant("Other Recall Tenant");
    assertStatus(api.get(other.id(), "/recalls/" + recall), 404);
    assertStatus(
        api.post(
            other.id(), "/recalls/" + recall + ":analyze-impact", Map.of("page", 0, "size", 100)),
        404);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM recall_hold_result WHERE organization_id=? AND execution_id=?",
                    tenant.id(),
                    execution))
        .isInstanceOf(DataAccessException.class);
  }
}
