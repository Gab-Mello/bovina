package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.support.fixture.DistributionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShipmentComplianceIT extends AuthenticatedIntegrationTest {
  @Test
  void unverifiedDocumentMatrixBlocksRegulatedDispatchRatherThanTreatingPresenceAsPass()
      throws Exception {
    var tenant = tenant("Regulated Dispatch Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var recipient = DistributionFixtures.recipient(api, tenant);
    var doc = id();
    var version = id();
    assertStatus(
        api.post(tenant.id(), "/documents", doc, Map.of("id", doc, "typeCode", "GTA")), 201);
    assertStatus(
        api.putDocumentVersion(
            tenant.id(),
            doc,
            version,
            0,
            "reference.txt",
            "text/plain",
            "unverified external evidence".getBytes()),
        201);
    var shipment = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment,
                recipient,
                stock,
                List.of(DistributionFixtures.item(stock)),
                List.of(
                    Map.of(
                        "id", id(), "typeCode", "GTA", "documentId", doc, "versionId", version)))),
        201);
    var evaluation =
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":validate",
            Map.of("movementAt", DistributionFixtures.DISPATCHED_AT));
    assertStatus(evaluation, 200);
    assertThat(json.readTree(evaluation.body()).path("evaluation").path("result").asString())
        .isEqualTo("UNKNOWN");
    var rejected =
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch());
    assertStatus(rejected, 409);
    assertThat(rejected.body()).contains("MOVEMENT_DOCUMENT_REQUIREMENTS_UNVERIFIED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND movement_type='SHIP'",
                Integer.class,
                tenant.id()))
        .isZero();
    assertThat(
            json.readTree(api.get(tenant.id(), "/shipments/" + shipment).body())
                .path("status")
                .asString())
        .isEqualTo("DRAFT");
  }
}
