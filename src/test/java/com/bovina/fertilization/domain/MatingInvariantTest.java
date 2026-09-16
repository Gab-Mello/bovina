package com.bovina.fertilization.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatingInvariantTest {
  private final StableIds ids = new StableIds();

  @Test
  void requiresPositiveAllocationAndNormalizesMethod() {
    var mating =
        new Mating(
            ids.next(),
            ids.next(),
            ids.next(),
            2,
            Instant.parse("2026-09-14T12:00:00Z"),
            "ivf",
            null,
            null,
            0,
            DataProvenance.manual(ids.next(), Instant.EPOCH));
    assertThat(mating.method()).isEqualTo("IVF");
    assertThat(mating.status()).isEqualTo(Mating.Status.FERTILIZED);

    assertThatThrownBy(
            () ->
                new Mating(
                    ids.next(),
                    ids.next(),
                    ids.next(),
                    0,
                    Instant.EPOCH,
                    "IVF",
                    null,
                    null,
                    0,
                    DataProvenance.manual(ids.next(), Instant.EPOCH)))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_MATING");
  }

  @Test
  void batchRequiresDistinctStableItemAndMatingIds() {
    var duplicate = ids.next();
    var source = new MatingBatch.Source(DataProvenance.Origin.MANUAL, null, null);
    var items =
        List.of(
            new MatingBatch.Item(
                ids.next(), duplicate, ids.next(), ids.next(), 1, Instant.EPOCH, "IVF", null),
            new MatingBatch.Item(
                ids.next(), duplicate, ids.next(), ids.next(), 1, Instant.EPOCH, "IVF", null));
    assertThatThrownBy(() -> new MatingBatch(ids.next(), source, items))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_MATING_BATCH");
  }
}
