package com.bovina.parties.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class CounterpartyLifecycleTest {
  @Test
  void archivedIdentityCannotReceiveAssignments() {
    var ids = new StableIds();
    var party =
        Party.registerClient(
            ids.next(),
            ids.next(),
            ClientType.PERSON,
            " Owner ",
            Instant.EPOCH,
            DataProvenance.manual(ids.next(), Instant.EPOCH));
    assertThat(party.displayName()).isEqualTo("Owner");
    assertThatThrownBy(() -> party.archive(0))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("CONCURRENT_WRITE_CONFLICT");
  }

  @Test
  void halfOpenPeriodsAllowAdjacentAssignmentsButRejectReversedDates() {
    var start = LocalDate.of(2026, 1, 1);
    var first = new EffectivePeriod(start, start.plusDays(1));
    assertThat(first.overlaps(new EffectivePeriod(start.plusDays(1), null))).isFalse();
    assertThat(first.overlaps(new EffectivePeriod(start, null))).isTrue();
    assertThatThrownBy(() -> new EffectivePeriod(start, start))
        .isInstanceOf(ApplicationFailure.class);
  }
}
