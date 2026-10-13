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
  void dispositionQuantitiesCannotWrapAroundAndCompleteMatingAsZero() throws Exception {
    var tenant = tenant("Disposition Reconciliation Lab");
    var inputs = ProductionFixtures.fertilizationInputs(api, tenant, 1);
    var mating = id();
    var matingBatch = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/matings:bulk",
            matingBatch,
            Map.of(
                "batchId",
                matingBatch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        mating,
                        "collectionId",
                        inputs.collection(),
                        "semenBatchId",
                        inputs.semenBatch(),
                        "allocatedOocytes",
                        1,
                        "fertilizedAt",
                        Instant.EPOCH,
                        "method",
                        "IVF",
                        "responsibleProfessionalId",
                        inputs.professional())))),
        200);
    var completionKey = id();
    var dispositions =
        List.of(
            Map.of("id", id(), "code", "NOT_INDIVIDUALIZED", "quantity", Integer.MAX_VALUE),
            Map.of("id", id(), "code", "NOT_INDIVIDUALIZED", "quantity", Integer.MAX_VALUE),
            Map.of("id", id(), "code", "NOT_INDIVIDUALIZED", "quantity", 2));
    assertStatus(
        api.post(
            tenant.id(),
            "/matings/" + mating + ":complete-embryology",
            completionKey,
            Map.of("expectedVersion", 0, "producedCount", 0, "dispositions", dispositions)),
        422);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM aggregate_embryo_disposition WHERE organization_id=? AND mating_id=?",
                Integer.class,
                tenant.id(),
                mating))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mating_completion WHERE organization_id=? AND mating_id=?",
                Integer.class,
                tenant.id(),
                mating))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_type='MATING' AND entity_id=? AND action='COMPLETE'",
                Integer.class,
                tenant.id(),
                mating))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotent_command WHERE organization_id=? AND command_type='COMPLETE_MATING_EMBRYOLOGY_V1' AND idempotency_key=?",
                Integer.class,
                tenant.id(),
                completionKey))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM mating WHERE organization_id=? AND id=?",
                String.class,
                tenant.id(),
                mating))
        .isEqualTo("FERTILIZED");
  }

  @Test
  void identificationRejectsPreFertilizationDatesAtomicallyAndAcceptsEqualOrLaterDates()
      throws Exception {
    var tenant = tenant("Embryo Chronology Lab");
    var inputs = ProductionFixtures.fertilizationInputs(api, tenant, 3);
    var mating = id();
    var matingBatch = id();
    var fertilizedAt = Instant.parse("2026-09-01T10:00:00Z");
    assertStatus(
        api.post(
            tenant.id(),
            "/matings:bulk",
            matingBatch,
            Map.of(
                "batchId",
                matingBatch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        mating,
                        "collectionId",
                        inputs.collection(),
                        "semenBatchId",
                        inputs.semenBatch(),
                        "allocatedOocytes",
                        3,
                        "fertilizedAt",
                        fertilizedAt,
                        "method",
                        "IVF",
                        "responsibleProfessionalId",
                        inputs.professional())))),
        200);
    var firstEmbryo = id();
    var invalidEmbryo = id();
    var invalidBatch = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryos:bulk",
            invalidBatch,
            Map.of(
                "batchId",
                invalidBatch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        firstEmbryo,
                        "matingId",
                        mating,
                        "humanCode",
                        "CHRONOLOGY-EQUAL",
                        "identifiedAt",
                        fertilizedAt),
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        invalidEmbryo,
                        "matingId",
                        mating,
                        "humanCode",
                        "CHRONOLOGY-BEFORE",
                        "identifiedAt",
                        fertilizedAt.minusNanos(1_000))))),
        422);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo WHERE organization_id=? AND mating_id=?",
                Integer.class,
                tenant.id(),
                mating))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_type='EMBRYO' AND entity_id IN (?,?)",
                Integer.class,
                tenant.id(),
                firstEmbryo,
                invalidEmbryo))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotent_command WHERE organization_id=? AND command_type='IDENTIFY_EMBRYOS_V1' AND idempotency_key=?",
                Integer.class,
                tenant.id(),
                invalidBatch))
        .isZero();

    var laterEmbryo = id();
    var validBatch = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/embryos:bulk",
            validBatch,
            Map.of(
                "batchId",
                validBatch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        firstEmbryo,
                        "matingId",
                        mating,
                        "humanCode",
                        "CHRONOLOGY-EQUAL",
                        "identifiedAt",
                        fertilizedAt),
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        laterEmbryo,
                        "matingId",
                        mating,
                        "humanCode",
                        "CHRONOLOGY-LATER",
                        "identifiedAt",
                        fertilizedAt.plusNanos(1_000))))),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo WHERE organization_id=? AND mating_id=?",
                Integer.class,
                tenant.id(),
                mating))
        .isEqualTo(2);
  }

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
