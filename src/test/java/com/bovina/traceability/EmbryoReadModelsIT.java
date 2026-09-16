package com.bovina.traceability;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.*;
import com.bovina.support.integration.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(ReadQueryCounting.class)
class EmbryoReadModelsIT extends AuthenticatedIntegrationTest {
  @Autowired private ReadQueryCounting.Counter queries;

  @Test
  void paginatedEmbryosHaveStableOrderingLiteralSearchAndConstantQueryCost() throws Exception {
    var tenant = tenant("Read Models Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 40);
    var url = "/embryos?matingId=" + production.mating();
    assertStatus(api.get(tenant.id(), url + "&size=1"), 200);
    queries.reset();
    var small = api.get(tenant.id(), url + "&size=1");
    var smallCost = queries.count();
    queries.reset();
    var large = api.get(tenant.id(), url + "&size=20");
    assertStatus(large, 200);
    assertThat(queries.count()).as("Queries must not grow with page size").isEqualTo(smallCost);
    assertThat(smallCost)
        .as("Bounded real JDBC query budget, including membership authorization")
        .isLessThanOrEqualTo(12);
    var first = json.readTree(large.body()).path("items");
    assertThat(first.size()).isEqualTo(20);
    var second = json.readTree(api.get(tenant.id(), url + "&page=1&size=20").body()).path("items");
    var found = new ArrayList<String>();
    first.forEach(e -> found.add(e.path("id").asString()));
    second.forEach(e -> found.add(e.path("id").asString()));
    assertThat(found).hasSize(40).doesNotHaveDuplicates().isSorted();
    assertThat(json.readTree(small.body()).path("items").get(0).path("id").asString())
        .isEqualTo(found.getFirst());
    assertThat(
            json.readTree(api.get(tenant.id(), "/embryos?q=" + found.getFirst()).body())
                .path("items")
                .size())
        .isEqualTo(1);
    var code = "FRESH-" + found.getFirst();
    var search = api.get(tenant.id(), "/embryos?q=" + code.toLowerCase(Locale.ROOT));
    assertStatus(search, 200);
    assertThat(json.readTree(search.body()).path("items").size()).isEqualTo(1);
    assertThat(json.readTree(api.get(tenant.id(), "/embryos?q=%25").body()).path("items"))
        .isEmpty();
    assertStatus(api.get(tenant.id(), "/embryos?size=101"), 422);
    var other = tenant("Other Read Models Lab");
    assertThat(json.readTree(api.get(other.id(), url).body()).path("items")).isEmpty();
  }

  @Test
  void freshTransferTimelinePreservesChecksAndInvalidationsAndCanonicalLineage() throws Exception {
    var tenant = tenant("Fresh Follow-up Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var embryo = production.embryos().getFirst();
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient A");
    var cycle = TransferFixtures.openCycle(api, tenant.id(), recipient, LocalDate.of(2020, 1, 1));
    var reservation = TransferFixtures.reservation(api, tenant.id(), embryo, cycle, 0);
    var transfer =
        TransferFixtures.perform(
            api,
            tenant.id(),
            reservation.id(),
            1,
            production.professional(),
            Instant.parse("2020-01-02T12:00:00Z"));
    var check = id();
    var batch = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/pregnancy-checks:bulk",
            batch,
            TransferFixtures.checkBatch(
                batch,
                TransferFixtures.checkItem(
                    check,
                    transfer.id(),
                    Instant.parse("2020-02-01T12:00:00Z"),
                    "PREGNANT",
                    production.professional(),
                    null,
                    null))),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/pregnancy-checks/" + check + ":invalidate",
            Map.of("reason", "Incorrect recorded result")),
        200);
    var lineage = api.get(tenant.id(), "/embryos/" + embryo + "/traceability");
    assertStatus(lineage, 200);
    var body = json.readTree(lineage.body());
    for (var expected :
        Map.of(
                "matingId",
                production.mating(),
                "collectionId",
                production.collection(),
                "donorId",
                production.donor(),
                "semenBatchId",
                production.semenBatch(),
                "sireId",
                production.sire(),
                "transferId",
                transfer.id(),
                "recipientAnimalId",
                recipient)
            .entrySet())
      assertThat(body.path(expected.getKey()).asString()).isEqualTo(expected.getValue().toString());
    var timeline = api.get(tenant.id(), "/embryos/" + embryo + "/timeline?size=100");
    assertStatus(timeline, 200);
    var types = new ArrayList<String>();
    json.readTree(timeline.body()).path("items").forEach(e -> types.add(e.path("type").asString()));
    assertThat(types)
        .containsExactly("IDENTIFIED", "TRANSFERRED", "PREGNANCY_CHECK", "CHECK_INVALIDATED");
    var page = api.get(tenant.id(), "/embryos/" + embryo + "/timeline?page=1&size=2");
    assertStatus(page, 200);
    assertThat(json.readTree(page.body()).path("items").get(0).path("factId").asString())
        .isEqualTo(check.toString());
    var other = tenant("Other Follow-up Lab");
    assertStatus(api.get(other.id(), "/embryos/" + embryo + "/traceability"), 404);
    assertStatus(api.get(other.id(), "/embryos/" + embryo + "/timeline"), 404);
  }

  @Test
  void cryogenicTimelineUsesPhysicalFactsAndPackageSearchUsesCurrentProjection() throws Exception {
    var tenant = tenant("Stored Material Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var embryo = stock.production().embryos().getFirst();
    var lineage = api.get(tenant.id(), "/embryos/" + embryo + "/traceability");
    assertStatus(lineage, 200);
    assertThat(json.readTree(lineage.body()).path("currentPackageId").asString())
        .isEqualTo(stock.packaged().packageId().toString());
    assertThat(json.readTree(lineage.body()).path("currentLocationId").asString())
        .isEqualTo(stock.location().toString());
    var timeline = api.get(tenant.id(), "/embryos/" + embryo + "/timeline?size=100");
    assertStatus(timeline, 200);
    var types = new ArrayList<String>();
    json.readTree(timeline.body()).path("items").forEach(e -> types.add(e.path("type").asString()));
    assertThat(types).contains("IDENTIFIED", "EVALUATED", "CRYOPRESERVED", "PACKAGED", "STORE");
    var page = api.get(tenant.id(), "/embryo-packages?locationId=" + stock.location());
    assertStatus(page, 200);
    assertThat(json.readTree(page.body()).path("items").size()).isEqualTo(1);
    assertThat(
            json.readTree(api.get(tenant.id(), "/embryo-packages?locationId=" + id()).body())
                .path("items"))
        .isEmpty();
  }
}
