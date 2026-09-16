package com.bovina.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.DistributionFixtures;
import com.bovina.support.integration.OperationalDistributionTest;
import java.util.*;
import org.junit.jupiter.api.Test;

class OperationalSearchIT extends OperationalDistributionTest {
  @Test
  void searchesAreBoundedLiteralTenantScopedAndUseDispatchedDestinationEvidence() throws Exception {
    var tenant = tenant("Searchable Material Lab");
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
    var dispatch =
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch());
    assertStatus(dispatch, 200);
    assertStatus(
        api.post(
            tenant.id(),
            "/clients/" + recipient + ":update",
            Map.of("expectedVersion", 1, "displayName", "Renamed destination")),
        200);
    var snapshot = api.get(tenant.id(), "/shipments?q=Destination%20Lab&size=1");
    assertStatus(snapshot, 200);
    assertThat(json.readTree(snapshot.body()).path("items").get(0).path("id").asString())
        .isEqualTo(shipment.toString());
    assertThat(json.readTree(api.get(tenant.id(), "/shipments?q=Renamed").body()).path("items"))
        .isEmpty();
    assertThat(
            json.readTree(api.get(tenant.id(), "/shipments?q=SHIPPED").body()).path("items").size())
        .isEqualTo(1);
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
                "DONOR",
                "triggerId",
                stock.production().donor(),
                "reason",
                "Review supplier evidence")),
        201);
    var recalls = api.get(tenant.id(), "/recalls?q=supplier");
    assertStatus(recalls, 200);
    assertThat(json.readTree(recalls.body()).path("items").get(0).path("id").asString())
        .isEqualTo(recall.toString());
    var semen = api.get(tenant.id(), "/semen-batches/" + stock.production().semenBatch());
    assertStatus(semen, 200);
    var code = json.readTree(semen.body()).path("batchCode").asString();
    var batches = api.get(tenant.id(), "/semen-batches?q=" + code.toLowerCase(Locale.ROOT));
    assertStatus(batches, 200);
    assertThat(json.readTree(batches.body()).path("items").size()).isEqualTo(1);
    for (var path : List.of("/shipments", "/recalls", "/semen-batches", "/embryo-packages")) {
      var literal = api.get(tenant.id(), path + "?q=%25");
      assertStatus(literal, 200);
      assertThat(json.readTree(literal.body()).path("items"))
          .as(path + " must not treat user % as a wildcard")
          .isEmpty();
      assertStatus(api.get(tenant.id(), path + "?size=101"), 422);
    }
    var other = tenant("Other Search Lab");
    for (var path : List.of("/shipments", "/recalls", "/semen-batches", "/embryo-packages"))
      assertThat(json.readTree(api.get(other.id(), path).body()).path("items"))
          .as(path + " remains tenant scoped")
          .isEmpty();
  }
}
