package com.bovina.embryology.application;

import com.bovina.audit.application.*;
import com.bovina.embryology.infrastructure.EmbryologyFacts;
import com.bovina.fertilization.application.Matings;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompleteMating {
  private final TenantAccess access;
  private final Matings matings;
  private final EmbryologyFacts facts;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public CompleteMating(
      TenantAccess access,
      Matings matings,
      EmbryologyFacts facts,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.matings = matings;
    this.facts = facts;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Result complete(ExecutionContext c, UUID key, UUID matingId, Command command) {
    access.require(c, "embryology:write");
    return receipts.replayOrExecute(
        c,
        key,
        "COMPLETE_MATING_EMBRYOLOGY_V1",
        new Intent(matingId, command),
        Result.class,
        () -> {
          var mating = matings.lockForEmbryology(c.tenantId(), matingId);
          if (command.expectedVersion() != mating.version())
            throw conflict("STALE_MATING_VERSION", "Mating version has changed");
          if (command.producedCount() < 0 || command.producedCount() > mating.allocatedOocytes())
            throw rejected(
                "INVALID_PRODUCED_COUNT", "Produced count must be within allocated oocytes");
          var dispositions =
              command.dispositions() == null
                  ? List.<EmbryologyFacts.Disposition>of()
                  : List.copyOf(command.dispositions());
          var idsSeen = new HashSet<UUID>();
          for (var d : dispositions)
            if (!idsSeen.add(d.id()))
              throw rejected("DUPLICATE_DISPOSITION_ID", "Disposition IDs must be distinct");
          var individualized = Math.toIntExact(facts.embryoCount(c.tenantId(), matingId));
          var aggregate =
              dispositions.stream().mapToInt(EmbryologyFacts.Disposition::quantity).sum();
          if (command.producedCount() != individualized + aggregate)
            throw rejected(
                "EMBRYO_RECONCILIATION_MISMATCH",
                "Produced count must equal individualized embryos plus aggregate dispositions");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          facts.complete(
              c.tenantId(), matingId, command.producedCount(), dispositions, c.actorId(), now);
          matings.completeEmbryology(c.tenantId(), matingId, mating.version());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "COMPLETE",
                  "MATING",
                  matingId,
                  mating.version() + 1,
                  null,
                  "FERTILIZED",
                  "COMPLETED"));
          return new Result(
              matingId, command.producedCount(), individualized, aggregate, "COMPLETED", now);
        });
  }

  private static ApplicationFailure rejected(String code, String detail) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, detail);
  }

  private static ApplicationFailure conflict(String code, String detail) {
    return new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, code, detail);
  }

  public record Command(
      long expectedVersion, int producedCount, List<EmbryologyFacts.Disposition> dispositions) {
    public Command {
      if (dispositions != null) dispositions = List.copyOf(dispositions);
    }
  }

  public record Result(
      UUID matingId,
      int producedCount,
      int individualizedCount,
      int aggregateDispositionCount,
      String status,
      Instant completedAt) {}

  private record Intent(UUID matingId, Command command) {}
}
