package com.bovina.platform.application;

import com.bovina.platform.infrastructure.CommandReceiptStore;
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

  public CommandReceipts(CommandReceiptStore store, StableIds ids, Clock clock) {
    this.store = store;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T replayOrExecute(
      ExecutionContext context,
      UUID key,
      String operation,
      Object input,
      Class<T> resultType,
      Supplier<T> mutation) {
    StableIds.requireVersion7(key);
    var hash = store.hash(context.actorId(), operation, input);
    if (!store.claim(ids.next(), context, key, operation, hash, clock.instant()))
      return store.replay(context.tenantId(), key, operation, hash, resultType);
    var result = mutation.get();
    store.complete(context.tenantId(), key, operation, result, clock.instant());
    return result;
  }
}
