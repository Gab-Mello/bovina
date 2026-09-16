package com.bovina.platform.application;

import com.bovina.platform.infrastructure.CommandReceiptStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Replay storage participates in the caller's business transaction, including rollback. */
@Component
public class CommandReceipts {
  private final CommandReceiptStore store;
  private final StableIds ids;
  private final Clock clock;
  private final MeterRegistry meters;

  public CommandReceipts(
      CommandReceiptStore store, StableIds ids, Clock clock, MeterRegistry meters) {
    this.store = store;
    this.ids = ids;
    this.clock = clock;
    this.meters = meters;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T replayOrExecute(
      ExecutionContext context,
      UUID key,
      String operation,
      Object input,
      Class<T> resultType,
      Supplier<T> mutation) {
    var sample = Timer.start(meters);
    var outcome = "ERROR";
    try {
      StableIds.requireVersion7(key);
      var hash = store.hash(context.actorId(), operation, input);
      if (!store.claim(ids.next(), context, key, operation, hash, clock.instant())) {
        var result = store.replay(context.tenantId(), key, operation, hash, resultType);
        outcome = "REPLAYED";
        return result;
      }
      var result = mutation.get();
      store.complete(context.tenantId(), key, operation, result, clock.instant());
      outcome = "APPLIED";
      return result;
    } catch (ApplicationFailure failure) {
      outcome = failure.kind().name();
      throw failure;
    } finally {
      // Attempts are not committed facts: the caller still owns commit/rollback.
      sample.stop(
          Timer.builder("bovina.command.attempts")
              .tag("operation", operation)
              .tag("outcome", outcome)
              .register(meters));
    }
  }
}
