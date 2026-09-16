package com.bovina.opu.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class IntakeObservationTest {
  private final StableIds ids = new StableIds();

  @Test
  void transportHasNoUniversalDurationOrQuantityEqualityRule() {
    var items = new ArrayList<>(List.of(new TransportReceipt.Item(ids.next(), 10, 6)));
    var r =
        new TransportReceipt(
            ids.next(),
            ids.next(),
            ids.next(),
            null,
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(200000),
            null,
            null,
            null,
            null,
            items);
    items.clear();
    assertThat(r.items()).hasSize(1);
    assertThatThrownBy(
            () ->
                new TransportReceipt(
                    ids.next(),
                    ids.next(),
                    ids.next(),
                    null,
                    Instant.EPOCH,
                    Instant.EPOCH.minusSeconds(1),
                    null,
                    null,
                    null,
                    null,
                    r.items()))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new TransportReceipt.Item(ids.next(), -1, 0))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void externalCountCannotBeMissingOrNegative() {
    assertThatThrownBy(
            () ->
                new ExternalOocyteReceipt(
                    ids.next(),
                    Instant.EPOCH,
                    "external reference",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () ->
                new ExternalOocyteReceipt(
                    ids.next(),
                    Instant.EPOCH,
                    "external reference",
                    null,
                    null,
                    null,
                    -1,
                    null,
                    null))
        .isInstanceOf(ApplicationFailure.class);
  }
}
