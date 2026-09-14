package com.bovina.transfer.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import org.junit.jupiter.api.Test;

class RecipientCycleTest {
  private static final StableIds IDS = new StableIds();

  @Test
  void closesWithoutLosingItsOpeningFact() {
    var actor = IDS.next();
    var cycle =
        new RecipientCycle(
            IDS.next(),
            new RecipientCycle.Registration(
                IDS.next(), IDS.next(), null, LocalDate.of(2026, 9, 1), "Observed cycle"),
            DataProvenance.manual(actor, Instant.EPOCH));

    cycle.close(0, LocalDate.of(2026, 9, 4), "Cycle completed");

    assertThat(cycle.status()).isEqualTo(RecipientCycle.Status.CLOSED);
    assertThat(cycle.openedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(cycle.closedOn()).isEqualTo(LocalDate.of(2026, 9, 4));
  }

  @Test
  void rejectsClosingBeforeOpening() {
    var cycle =
        new RecipientCycle(
            IDS.next(),
            new RecipientCycle.Registration(
                IDS.next(), IDS.next(), null, LocalDate.of(2026, 9, 4), null),
            DataProvenance.manual(IDS.next(), Instant.EPOCH));

    assertThatThrownBy(() -> cycle.close(0, LocalDate.of(2026, 9, 3), "Invalid"))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_CYCLE_CLOSE_DATE");
  }
}
