package com.bovina.platform.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bovina.platform.infrastructure.CommandReceiptStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandMetricsTest {
  @Test
  void commandAttemptMetricsDistinguishExecutionReplayAndConflictWithoutIdentityLabels() {
    var store = mock(CommandReceiptStore.class);
    var meters = new SimpleMeterRegistry();
    var ids = new StableIds();
    var context = new ExecutionContext(ids.next(), ids.next(), Set.of(), ids.next());
    var key = ids.next();
    var receipts =
        new CommandReceipts(store, ids, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), meters);
    when(store.hash(any(), anyString(), any())).thenReturn("hash");
    when(store.claim(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(true, false, false);
    when(store.replay(context.tenantId(), key, "RECORD_CHECK_V1", "hash", String.class))
        .thenReturn("result")
        .thenThrow(
            new ApplicationFailure(
                ApplicationFailure.Kind.CONFLICT, "IDEMPOTENCY_CONFLICT", "Different intent"));

    assertThat(
            receipts.replayOrExecute(
                context, key, "RECORD_CHECK_V1", "input", String.class, () -> "result"))
        .isEqualTo("result");
    assertThat(
            receipts.replayOrExecute(
                context,
                key,
                "RECORD_CHECK_V1",
                "input",
                String.class,
                () -> {
                  throw new AssertionError("Replay must not execute mutation");
                }))
        .isEqualTo("result");
    assertThatThrownBy(
            () ->
                receipts.replayOrExecute(
                    context, key, "RECORD_CHECK_V1", "input", String.class, () -> "wrong"))
        .isInstanceOf(ApplicationFailure.class);
    for (var outcome : Set.of("APPLIED", "REPLAYED", "CONFLICT")) {
      var timer =
          meters
              .get("bovina.command.attempts")
              .tag("operation", "RECORD_CHECK_V1")
              .tag("outcome", outcome)
              .timer();
      assertThat(timer.count()).isEqualTo(1);
      assertThat(timer.getId().getTags())
          .extracting(io.micrometer.core.instrument.Tag::getKey)
          .containsExactly("operation", "outcome");
    }
  }
}
