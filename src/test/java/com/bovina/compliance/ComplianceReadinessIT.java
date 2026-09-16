package com.bovina.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComplianceReadinessIT extends AuthenticatedIntegrationTest {
  @Test
  void verifiedNarrowRuleKeepsUnverifiedRequirementsUnknownAndHistoricalFactsNotApplicable()
      throws Exception {
    var lab = tenant("Compliance Readiness Lab");
    var other = tenant("Other Compliance Tenant");
    var inputs = ProductionFixtures.fertilizationInputs(api, lab, 2);
    var current = id();
    var historical = id();
    var batch = id();
    assertStatus(
        api.post(
            lab.id(),
            "/matings:bulk",
            batch,
            Map.of(
                "batchId",
                batch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        current,
                        "collectionId",
                        inputs.collection(),
                        "semenBatchId",
                        inputs.semenBatch(),
                        "allocatedOocytes",
                        1,
                        "fertilizedAt",
                        Instant.parse("2026-09-15T12:00:00Z"),
                        "method",
                        "IVF"),
                    Map.of(
                        "itemId",
                        id(),
                        "id",
                        historical,
                        "collectionId",
                        inputs.collection(),
                        "semenBatchId",
                        inputs.semenBatch(),
                        "allocatedOocytes",
                        1,
                        "fertilizedAt",
                        Instant.parse("2024-12-01T12:00:00Z"),
                        "method",
                        "IVF")))),
        200);

    var verifiedRule = "CPIVE_A28_SEMEN_FERTILIZATION_FIELDS";
    var currentResult =
        api.post(
            lab.id(),
            "/compliance/evaluations:preview",
            Map.of("ruleKey", verifiedRule, "subjectType", "MATING", "subjectId", current));
    assertStatus(currentResult, 200);
    assertThat(currentResult.body())
        .contains("\"result\":\"PASS\"", "RECORDED_FIELDS_PRESENT_NOT_OVERALL_COMPLIANCE")
        .contains("PORTARIA_SDA_MAPA_1204_2024", "ART_28_IV_V_VI");
    assertThat(api.get(lab.id(), "/compliance/rules").body())
        .contains(verifiedRule, "2024-12-02", "ART_28_IV_V_VI");

    var oldResult =
        api.post(
            lab.id(),
            "/compliance/evaluations:preview",
            Map.of("ruleKey", verifiedRule, "subjectType", "MATING", "subjectId", historical));
    assertStatus(oldResult, 200);
    assertThat(oldResult.body()).contains("\"result\":\"NOT_APPLICABLE\"");

    var unknown =
        api.post(
            lab.id(),
            "/compliance/evaluations:preview",
            Map.of(
                "ruleKey",
                "UNVERIFIED_SANITARY_SPEC",
                "subjectType",
                "MATING",
                "subjectId",
                current));
    assertStatus(unknown, 200);
    assertThat(unknown.body()).contains("\"result\":\"UNKNOWN\"", "RULE_SPEC_NOT_VERIFIED");
    assertStatus(
        api.post(
            other.id(),
            "/compliance/evaluations:preview",
            Map.of("ruleKey", verifiedRule, "subjectType", "MATING", "subjectId", current)),
        404);
  }
}
