package com.bovina.embryology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbryoLifecycleIT extends AuthenticatedIntegrationTest {
  @Test
  void evaluationsDispositionAndHoldRemainOrthogonalAndHistoricallyImmutable() throws Exception {
    var production = ProductionFixtures.freshEmbryos(api, tenant("Embryo Assessment Lab"), 3, 2);
    var embryo = production.embryos().getFirst();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo WHERE mating_id=?",
                Integer.class,
                production.mating()))
        .isEqualTo(2);
    var scheme = id();
    assertStatus(
        api.post(
            production.tenant().id(),
            "/assessment-schemes",
            Map.of("id", scheme, "code", "LAB_SCHEME", "name", "Lab scheme")),
        201);
    var version = id();
    var stage = id();
    var grade = id();
    var published =
        api.post(
            production.tenant().id(),
            "/assessment-schemes/" + scheme + "/versions",
            Map.of(
                "id",
                version,
                "versionLabel",
                "2026.1",
                "codes",
                List.of(
                    Map.of(
                        "id",
                        stage,
                        "dimension",
                        "DEVELOPMENT_STAGE",
                        "code",
                        "MORULA",
                        "displayName",
                        "Morula",
                        "sortOrder",
                        1),
                    Map.of(
                        "id",
                        grade,
                        "dimension",
                        "QUALITY_GRADE",
                        "code",
                        "A",
                        "displayName",
                        "Grade A",
                        "sortOrder",
                        1))));
    assertStatus(published, 201);
    assertThat(published.headers().firstValue("Location"))
        .contains("/api/v1/assessment-schemes/versions/" + version);

    var firstEvaluation = id();
    evaluate(production.tenant().id(), embryo, firstEvaluation, version, stage, grade, null);
    var currentEvaluation = id();
    evaluate(
        production.tenant().id(),
        embryo,
        currentEvaluation,
        version,
        stage,
        grade,
        firstEvaluation);
    assertThat(api.get(production.tenant().id(), "/embryos/" + embryo).body())
        .contains(
            "\"preservation\":\"FRESH\"",
            "\"currentLocationId\":null",
            currentEvaluation.toString());
    assertThat(
            json.readTree(
                    api.get(production.tenant().id(), "/embryos/" + embryo + "/evaluations").body())
                .size())
        .isEqualTo(2);

    var hold = id();
    assertStatus(
        api.post(
            production.tenant().id(),
            "/embryos/" + embryo + "/holds",
            Map.of("id", hold, "type", "QUALITY", "reason", "Review pending")),
        200);
    assertStatus(
        api.post(
            production.tenant().id(),
            "/embryos/" + embryo + ":discard",
            Map.of("expectedVersion", 0, "reason", "Blocked while on hold")),
        409);
    assertStatus(
        api.post(
            production.tenant().id(),
            "/embryos/" + embryo + "/holds/" + hold + ":release",
            Map.of("reason", "Review completed")),
        200);
    assertStatus(
        api.post(
            production.tenant().id(),
            "/embryos/" + embryo + ":discard",
            Map.of("expectedVersion", 0, "reason", "Assessment completed")),
        200);

    var disposition = id();
    var completeKey = id();
    var completion =
        Map.of(
            "expectedVersion",
            0,
            "producedCount",
            3,
            "dispositions",
            List.of(
                Map.of(
                    "id",
                    disposition,
                    "code",
                    "NOT_INDIVIDUALIZED",
                    "quantity",
                    1,
                    "reason",
                    "Observed aggregate outcome")));
    assertStatus(
        api.post(
            production.tenant().id(),
            "/matings/" + production.mating() + ":complete-embryology",
            completeKey,
            completion),
        200);
    assertStatus(
        api.post(
            production.tenant().id(),
            "/matings/" + production.mating() + ":complete-embryology",
            completeKey,
            completion),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT produced_count FROM mating_completion WHERE mating_id=?",
                Integer.class,
                production.mating()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT evaluation_id FROM embryo_current_assessment WHERE embryo_id=?",
                UUID.class,
                embryo))
        .isEqualTo(currentEvaluation);

    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE embryo_evaluation SET notes='rewrite' WHERE id='"
                          + firstEvaluation
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE assessment_code SET display_name='rewrite' WHERE id='" + stage + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  private void evaluate(
      UUID tenant,
      UUID embryo,
      UUID evaluation,
      UUID version,
      UUID stage,
      UUID grade,
      UUID supersedes)
      throws Exception {
    var batch = id();
    var item = new HashMap<String, Object>();
    item.put("itemId", id());
    item.put("id", evaluation);
    item.put("embryoId", embryo);
    item.put("schemeVersionId", version);
    item.put("developmentStageCodeId", stage);
    item.put("qualityGradeCodeId", grade);
    item.put("evaluatedAt", Instant.EPOCH);
    if (supersedes != null) {
      item.put("supersedesEvaluationId", supersedes);
    }
    assertStatus(
        api.post(
            tenant,
            "/embryo-evaluations:bulk",
            batch,
            Map.of("batchId", batch, "items", List.of(item))),
        200);
  }
}
