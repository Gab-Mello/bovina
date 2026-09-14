package com.bovina.transfer.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PregnancyOutcomeTest {
  private static final StableIds IDS = new StableIds();

  @Test
  void preservesTheOperationalDateAndRepresentsPregnancyLoss() {
    var check =
        check(
            PregnancyCheck.Result.PREGNANCY_LOSS,
            Instant.parse("2026-10-15T02:00:00Z"),
            null,
            null);

    assertThat(check.checkedOn()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(check.result()).isEqualTo(PregnancyCheck.Result.PREGNANCY_LOSS);
  }

  @Test
  void correctionMustExplicitlyReferenceAndExplainThePreviousFact() {
    assertThatThrownBy(() -> check(PregnancyCheck.Result.PREGNANT, Instant.EPOCH, IDS.next(), null))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_CHECK_CORRECTION");
  }

  @Test
  void batchRejectsTwoCorrectionsOfTheSameFact() {
    var previous = IDS.next();

    assertThatThrownBy(
            () -> new PregnancyCheckBatch(IDS.next(), List.of(item(previous), item(previous))))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("DUPLICATE_SUPERSEDED_CHECK");
  }

  private PregnancyCheck check(
      PregnancyCheck.Result result, Instant at, java.util.UUID supersedes, String reason) {
    return new PregnancyCheck(
        IDS.next(),
        IDS.next(),
        at,
        "America/Sao_Paulo",
        result,
        "ULTRASOUND",
        null,
        IDS.next(),
        supersedes,
        reason,
        DataProvenance.manual(IDS.next(), Instant.EPOCH));
  }

  private PregnancyCheckBatch.Item item(java.util.UUID previous) {
    return new PregnancyCheckBatch.Item(
        IDS.next(),
        IDS.next(),
        IDS.next(),
        Instant.EPOCH,
        "UTC",
        PregnancyCheck.Result.PREGNANT,
        "ULTRASOUND",
        null,
        IDS.next(),
        previous,
        "Corrected observation");
  }
}
