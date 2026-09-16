package com.bovina.opu.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.opu.application.CollectionBatch;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpuInvariantTest {
  private final StableIds ids = new StableIds();

  @Test
  void rejectsNegativeCountsAndViableAboveTotalWithoutInventingFollicles() {
    for (int[] pair : List.of(new int[] {-1, 0}, new int[] {2, -1}, new int[] {2, 3}))
      assertThatThrownBy(() -> new OocyteCounts(pair[0], pair[1], null))
          .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new OocyteCounts(2, 1, -1)).isInstanceOf(ApplicationFailure.class);
    assertThat(new OocyteCounts(2, 1, null).folliclesAspirated()).isNull();
    assertThat(new OocyteCounts(2, 1, 0).folliclesAspirated()).isZero();
  }

  @Test
  void observationTimesUsePostgresMicrosecondPrecision() {
    var time = Instant.parse("2026-09-14T10:00:00.123456789Z");
    var session =
        new OpuSession.Registration(
            ids.next(),
            ids.next(),
            null,
            ids.next(),
            null,
            ids.next(),
            time,
            "America/Sao_Paulo",
            null);
    var collection =
        new OocyteCollection.Registration(
            ids.next(), ids.next(), time, new OocyteCounts(1, 1, null), null);
    assertThat(session.performedAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00.123456Z"));
    assertThat(collection.collectedAt()).isEqualTo(session.performedAt());
  }

  @Test
  void availabilityUsesAllocationFactsAndRejectsOverAllocation() {
    var counts = new OocyteCounts(10, 8, null);
    assertThat(counts.availableAfter(3)).isEqualTo(5);
    counts.requireAllocation(3, 5);
    for (long allocated : List.of(-1L, 9L, Long.MAX_VALUE))
      assertThatThrownBy(() -> counts.availableAfter(allocated))
          .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> counts.requireAllocation(3, 6)).isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> counts.requireAllocation(0, 0)).isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void completedSessionCannotBeReopenedOrCancelled() {
    var s =
        new OpuSession(
            ids.next(),
            new OpuSession.Registration(
                ids.next(),
                ids.next(),
                null,
                ids.next(),
                null,
                ids.next(),
                Instant.EPOCH,
                "America/Sao_Paulo",
                null),
            DataProvenance.manual(ids.next(), Instant.EPOCH));
    assertThatThrownBy(() -> s.complete(0, ids.next(), Instant.EPOCH))
        .isInstanceOf(ApplicationFailure.class);
    s.start(0);
    s.complete(0, ids.next(), Instant.EPOCH);
    assertThatThrownBy(() -> s.start(0)).isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> s.cancel(0)).isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void correctionPreservesDonorAndFinalizationFreezesCounts() {
    var r =
        new OocyteCollection.Registration(
            ids.next(), ids.next(), Instant.EPOCH, new OocyteCounts(10, 8, null), null);
    var collection =
        new OocyteCollection(
            ids.next(), ids.next(), r, DataProvenance.manual(ids.next(), Instant.EPOCH));
    assertThatThrownBy(
            () -> collection.correct(0, new OocyteCounts(4, 3, null), null, "Count correction", 4))
        .isInstanceOf(ApplicationFailure.class);
    collection.correct(0, new OocyteCounts(9, 7, null), null, "Count correction", 0);
    assertThat(collection.donorId()).isEqualTo(r.donorId());
    assertThatThrownBy(collection::requireAllocatable).isInstanceOf(ApplicationFailure.class);
    collection.complete();
    collection.requireAllocatable();
    assertThatThrownBy(() -> collection.correct(0, r.counts(), null, "Again", 0))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void bulkRequiresBoundedDistinctItemsAndSourceEvidence() {
    var item =
        new CollectionBatch.Item(
            ids.next(), ids.next(), ids.next(), Instant.EPOCH, 2, 1, null, null);
    assertThatThrownBy(() -> new CollectionBatch(ids.next(), 0, null, List.of(item, item)))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new CollectionBatch.Source(DataProvenance.Origin.IMPORT, null, null))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () -> new CollectionBatch.Source(DataProvenance.Origin.API, ids.next(), null))
        .isInstanceOf(ApplicationFailure.class);
    var mutable = new ArrayList<>(List.of(item));
    var batch = new CollectionBatch(ids.next(), 0, null, mutable);
    mutable.clear();
    assertThat(batch.items()).hasSize(1);
  }
}
