package com.bovina.compliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.compliance.domain.RecordedSemenFertilizationFields.Result;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecordedSemenFertilizationFieldsTest {
  private final RecordedSemenFertilizationFields policy = new RecordedSemenFertilizationFields();
  private final ComplianceRuleDefinition rule =
      new ComplianceRuleDefinition(
          "CPIVE_A28_SEMEN_FERTILIZATION_FIELDS",
          "1204_2024",
          "MAPA",
          "PORTARIA_SDA_MAPA_1204_2024",
          "https://official.invalid/source",
          "ART_28_IV_V_VI",
          LocalDate.of(2024, 12, 2),
          null,
          "MATING",
          "INFORMATION",
          "RECORDED_SEMEN_FERTILIZATION_FIELDS");

  @Test
  void recordedFieldsPassOnlyTheNarrowVerifiedCompletenessRule() {
    assertThat(
            policy.evaluate(rule, LocalDate.of(2026, 9, 15), "SEMEN-LOT-A", "Semen Center A", true))
        .isEqualTo(Result.PASS);
  }

  @Test
  void missingBatchOrProducerIsFailureOfRecordedFieldPresence() {
    assertThat(policy.evaluate(rule, LocalDate.of(2026, 9, 15), null, "Semen Center A", true))
        .isEqualTo(Result.FAIL);
    assertThat(policy.evaluate(rule, LocalDate.of(2026, 9, 15), "SEMEN-LOT-A", " ", true))
        .isEqualTo(Result.FAIL);
  }

  @Test
  void sourceEffectiveDateBoundsApplicability() {
    assertThat(
            policy.evaluate(rule, LocalDate.of(2024, 12, 1), "SEMEN-LOT-A", "Semen Center A", true))
        .isEqualTo(Result.NOT_APPLICABLE);
    assertThat(
            policy.evaluate(rule, LocalDate.of(2024, 12, 2), "SEMEN-LOT-A", "Semen Center A", true))
        .isEqualTo(Result.PASS);
  }
}
