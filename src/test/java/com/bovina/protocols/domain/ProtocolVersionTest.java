package com.bovina.protocols.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.*;
import org.junit.jupiter.api.Test;

class ProtocolVersionTest {
  @Test
  void applicabilityRequiresMatchingPurposeAndEffectiveDate() {
    var ids = new StableIds();
    var from = LocalDate.of(2026, 1, 1);
    var until = from.plusMonths(1);
    var version =
        new ProtocolVersion(
            ids.next(),
            ids.next(),
            "1",
            new EffectivePeriod(from, until),
            "laboratory-controlled-reference",
            "a".repeat(64),
            null,
            ids.next(),
            Instant.EPOCH);
    version.requireApplicable("OOCYTE_TRANSPORT", "OOCYTE_TRANSPORT", from);
    assertThatThrownBy(
            () -> version.requireApplicable("OOCYTE_TRANSPORT", "CRYOPRESERVATION", from))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () -> version.requireApplicable("OOCYTE_TRANSPORT", "OOCYTE_TRANSPORT", until))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () ->
                version.requireApplicable(
                    "OOCYTE_TRANSPORT", "OOCYTE_TRANSPORT", from.minusDays(1)))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void publicationNeedsAnExactContentIdentityAndDoesNotAcceptArbitraryDigest() {
    var ids = new StableIds();
    assertThatThrownBy(
            () ->
                new ProtocolVersion(
                    ids.next(),
                    ids.next(),
                    "1",
                    new EffectivePeriod(LocalDate.of(2026, 1, 1), null),
                    "reference",
                    "wrong",
                    null,
                    ids.next(),
                    Instant.EPOCH))
        .isInstanceOf(ApplicationFailure.class);
  }
}
