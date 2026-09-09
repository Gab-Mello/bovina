package com.bovina.platform.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataProvenanceTest {
  private final UUID actor = UUID.randomUUID();
  private final Instant time = Instant.parse("2026-09-09T12:00:00Z");

  @Test
  void manualOriginKeepsServerRecordingIdentityAndTime() {
    var source = DataProvenance.manual(actor, time);
    assertThat(source.originType()).isEqualTo(DataProvenance.Origin.MANUAL);
    assertThat(source.recordedByUserId()).isEqualTo(actor);
    assertThat(source.recordedAt()).isEqualTo(time);
    assertThatThrownBy(() -> DataProvenance.manual(null, time))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nonManualOriginsCannotOmitTheirEvidence() {
    for (var origin : DataProvenance.Origin.values()) {
      if (origin == DataProvenance.Origin.MANUAL) continue;
      assertThatThrownBy(
              () -> new DataProvenance(origin, null, null, null, actor, time, null, null, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void confirmationRequiresBothActorAndTimeAndCannotBeInTheFuture() {
    assertThatThrownBy(
            () ->
                new DataProvenance(
                    DataProvenance.Origin.AI_EXTRACTED_CONFIRMED,
                    UUID.randomUUID(),
                    null,
                    null,
                    actor,
                    time,
                    actor,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DataProvenance(
                    DataProvenance.Origin.AI_EXTRACTED_CONFIRMED,
                    UUID.randomUUID(),
                    null,
                    null,
                    actor,
                    time,
                    actor,
                    time.plusSeconds(1),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new DataProvenance(
                    DataProvenance.Origin.AI_EXTRACTED_CONFIRMED,
                    UUID.randomUUID(),
                    null,
                    null,
                    actor,
                    time,
                    actor,
                    time,
                    null)
                .confirmedByUserId())
        .isEqualTo(actor);
  }

  @Test
  void explicitSourcesEnableTheirRespectiveOrigins() {
    assertThat(
            new DataProvenance(
                    DataProvenance.Origin.IMPORT,
                    null,
                    UUID.randomUUID(),
                    null,
                    actor,
                    time,
                    null,
                    null,
                    null)
                .originType())
        .isEqualTo(DataProvenance.Origin.IMPORT);
    assertThat(
            new DataProvenance(
                    DataProvenance.Origin.API,
                    null,
                    null,
                    UUID.randomUUID(),
                    actor,
                    time,
                    null,
                    null,
                    null)
                .originType())
        .isEqualTo(DataProvenance.Origin.API);
    assertThat(
            new DataProvenance(
                    DataProvenance.Origin.SYSTEM_DERIVED,
                    null,
                    null,
                    null,
                    actor,
                    time,
                    null,
                    null,
                    "calculation:v1:source-id")
                .originType())
        .isEqualTo(DataProvenance.Origin.SYSTEM_DERIVED);
  }
}
