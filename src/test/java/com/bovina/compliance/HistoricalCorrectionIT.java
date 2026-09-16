package com.bovina.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class HistoricalCorrectionIT extends AuthenticatedIntegrationTest {
  @Test
  void lineageCorrectionPreservesPreviousFactsAndExposesCurrentMaterialImpact() throws Exception {
    var lab = tenant("Historical Review Lab");
    var other = tenant("Other Historical Tenant");
    var production = ProductionFixtures.freshEmbryos(api, lab, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, lab, production.embryos().getFirst(), production, ids);
    var packaged = CryostorageFixtures.packagedEmbryo(api, lab, cryo, ids);
    var correction = id();
    var intent =
        Map.of(
            "id",
            correction,
            "subjectType",
            "MATING",
            "subjectId",
            production.mating(),
            "expectedSubjectVersion",
            0,
            "reason",
            "Source document requires review",
            "proposal",
            Map.of(
                "semantics", "Review declared semen provenance before any effective replacement"));
    assertStatus(api.post(lab.id(), "/corrections", correction, intent), 201);
    assertStatus(api.post(lab.id(), "/corrections", correction, intent), 201);
    var view = api.get(lab.id(), "/corrections/" + correction);
    assertStatus(view, 200);
    assertThat(view.body())
        .contains(production.semenBatch().toString(), "REQUESTED", "previousSemantics");
    var impact = api.get(lab.id(), "/corrections/" + correction + "/impact");
    assertStatus(impact, 200);
    assertThat(impact.body())
        .contains(
            "\"affectedEmbryos\":1",
            "\"activePackages\":1",
            "\"performedTransfers\":0",
            "BLOCKED_BY_DOMAIN_VALIDATION");
    assertStatus(api.get(other.id(), "/corrections/" + correction), 404);
    assertStatus(api.get(other.id(), "/corrections/" + correction + "/impact"), 404);
    assertThat(
            jdbc.queryForObject(
                "SELECT semen_batch_id FROM mating WHERE organization_id=? AND id=?",
                UUID.class,
                lab.id(),
                production.mating()))
        .isEqualTo(production.semenBatch());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record_correction WHERE organization_id=? AND id=?",
                Integer.class,
                lab.id(),
                correction))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=? AND action='CORRECT'",
                Integer.class,
                lab.id(),
                production.mating()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE record_correction SET reason='rewrite' WHERE organization_id=? AND id=?",
                    lab.id(),
                    correction))
        .isInstanceOf(DataAccessException.class);

    var stale = id();
    assertStatus(
        api.post(
            lab.id(),
            "/corrections",
            stale,
            Map.of(
                "id",
                stale,
                "subjectType",
                "MATING",
                "subjectId",
                production.mating(),
                "expectedSubjectVersion",
                99,
                "reason",
                "Stale intent",
                "proposal",
                Map.of("semantics", "Review"))),
        409);
    var illegal = id();
    assertStatus(
        api.post(
            other.id(),
            "/corrections",
            illegal,
            Map.of(
                "id",
                illegal,
                "subjectType",
                "MATING",
                "subjectId",
                production.mating(),
                "reason",
                "Foreign intent",
                "proposal",
                Map.of("semantics", "Review"))),
        404);
    for (var subject :
        List.of(
            Map.entry("CRYOPRESERVATION_ITEM", cryo.cryoItem()),
            Map.entry("PACKAGE_ITEM", packaged.packageItem()))) {
      var requested = id();
      assertStatus(
          api.post(
              lab.id(),
              "/corrections",
              requested,
              Map.of(
                  "id",
                  requested,
                  "subjectType",
                  subject.getKey(),
                  "subjectId",
                  subject.getValue(),
                  "reason",
                  "Observed record requires historical review",
                  "proposal",
                  Map.of("semantics", "Review without rewriting finalized fact"))),
          201);
      assertThat(api.get(lab.id(), "/corrections/" + requested).body())
          .contains("previousSemantics", "REQUESTED");
    }
  }

  @Test
  void concurrentReviewCreatesOneDecisionAndReplayDoesNotDuplicateAudit() throws Exception {
    var lab = tenant("Correction Decision Lab");
    var production = ProductionFixtures.freshEmbryos(api, lab, 1);
    var correction = id();
    assertStatus(
        api.post(
            lab.id(),
            "/corrections",
            correction,
            Map.of(
                "id",
                correction,
                "subjectType",
                "MATING",
                "subjectId",
                production.mating(),
                "reason",
                "Review request",
                "proposal",
                Map.of("semantics", "Possible declared source issue"))),
        201);
    var first = id();
    var second = id();
    var start = new CyclicBarrier(2);
    List<HttpResponse<String>> responses;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return api.post(
                    lab.id(),
                    "/corrections/" + correction + ":reject",
                    first,
                    Map.of("reason", "Evidence does not support replacement"));
              });
      var b =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return api.post(
                    lab.id(),
                    "/corrections/" + correction + ":reject",
                    second,
                    Map.of("reason", "Original record confirmed"));
              });
      responses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    var winner = responses.getFirst().statusCode() == 200 ? first : second;
    var reason =
        winner.equals(first)
            ? "Evidence does not support replacement"
            : "Original record confirmed";
    assertStatus(
        api.post(
            lab.id(), "/corrections/" + correction + ":reject", winner, Map.of("reason", reason)),
        200);
    assertThat(api.get(lab.id(), "/corrections/" + correction).body()).contains("REJECTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record_correction_review WHERE organization_id=? AND correction_id=?",
                Integer.class,
                lab.id(),
                correction))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=? AND action='REJECT'",
                Integer.class,
                lab.id(),
                correction))
        .isEqualTo(1);
  }
}
