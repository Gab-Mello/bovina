package com.bovina.transfer.application;

import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.transfer.domain.*;
import com.bovina.transfer.infrastructure.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PregnancyChecks {
  private final TenantAccess access;
  private final TransferStore transfers;
  private final PregnancyCheckStore checks;
  private final OpuFacilities facilities;
  private final OutcomeWindows windows;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public PregnancyChecks(
      TenantAccess access,
      TransferStore transfers,
      PregnancyCheckStore checks,
      OpuFacilities facilities,
      OutcomeWindows windows,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.transfers = transfers;
    this.checks = checks;
    this.facilities = facilities;
    this.windows = windows;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public PregnancyCheckBatch.Result record(
      ExecutionContext c, UUID key, PregnancyCheckBatch batch) {
    access.require(c, "transfer:write");
    if (!batch.batchId().equals(key))
      throw rejected("BATCH_KEY_MISMATCH", "Idempotency key must equal batch ID");
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_PREGNANCY_CHECKS_V1",
        batch,
        PregnancyCheckBatch.Result.class,
        () -> recordOnce(c, batch));
  }

  private PregnancyCheckBatch.Result recordOnce(ExecutionContext c, PregnancyCheckBatch batch) {
    var transferIds = batch.items().stream().map(PregnancyCheckBatch.Item::transferId).toList();
    var transferById = transfers.transfers(c.tenantId(), new HashSet<>(transferIds));
    if (transferById.size() != new HashSet<>(transferIds).size()) throw missing("EMBRYO_TRANSFER");
    var supersededIds =
        batch.items().stream()
            .map(PregnancyCheckBatch.Item::supersedesCheckId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    var superseded = checks.checks(c.tenantId(), supersededIds);
    if (superseded.size() != supersededIds.size()) throw missing("PREGNANCY_CHECK");
    if (!checks.invalidated(c.tenantId(), supersededIds).isEmpty())
      throw conflict("PREGNANCY_CHECK_ALREADY_INVALIDATED", "Check is already invalidated");
    batch.items().stream()
        .map(PregnancyCheckBatch.Item::professionalId)
        .distinct()
        .forEach(id -> facilities.requireProfessional(c.tenantId(), id));

    var now = now();
    var facts = new ArrayList<PregnancyCheck>(batch.items().size());
    var invalidations = new ArrayList<PregnancyCheckStore.Invalidation>();
    for (var item : batch.items()) {
      var transfer = transferById.get(item.transferId());
      if (item.checkedAt() == null || item.checkedAt().isBefore(transfer.performedAt()))
        throw rejected(
            "CHECK_PRECEDES_TRANSFER", "Pregnancy check cannot precede its embryo transfer");
      if (item.supersedesCheckId() != null) {
        var previous = superseded.get(item.supersedesCheckId());
        if (!previous.transferId().equals(item.transferId()))
          throw rejected(
              "CHECK_CORRECTION_TRANSFER_MISMATCH",
              "A correction must reference a check from the same transfer");
      }
      var fact =
          new PregnancyCheck(
              item.id(),
              item.transferId(),
              item.checkedAt().truncatedTo(ChronoUnit.MICROS),
              item.timezone(),
              item.result(),
              item.methodCode(),
              item.observations(),
              item.professionalId(),
              item.supersedesCheckId(),
              item.correctionReason(),
              DataProvenance.manual(c.actorId(), now));
      facts.add(fact);
      if (fact.supersedesCheckId() != null)
        invalidations.add(
            new PregnancyCheckStore.Invalidation(
                ids.next(),
                fact.transferId(),
                fact.supersedesCheckId(),
                fact.id(),
                fact.correctionReason(),
                c.actorId(),
                now));
    }
    checks.insert(c.tenantId(), facts);
    checks.invalidateAll(c.tenantId(), invalidations);
    audit.recordAll(
        facts.stream()
            .map(
                check ->
                    new AuditEvent(
                        ids.next(),
                        c,
                        now,
                        check.supersedesCheckId() == null ? "RECORD" : "CORRECT",
                        "PREGNANCY_CHECK",
                        check.id(),
                        null,
                        check.correctionReason(),
                        null,
                        check.result().name()))
            .toList());
    return new PregnancyCheckBatch.Result(
        batch.batchId(),
        batch.items().stream()
            .map(
                i ->
                    new PregnancyCheckBatch.ItemResult(
                        i.itemId(), i.id(), i.transferId(), "APPLIED"))
            .toList());
  }

  @Transactional
  public InvalidationView invalidate(ExecutionContext c, UUID key, UUID checkId, Invalidate input) {
    access.require(c, "transfer:write");
    return receipts.replayOrExecute(
        c,
        key,
        "INVALIDATE_PREGNANCY_CHECK_V1",
        new InvalidationIntent(checkId, input),
        InvalidationView.class,
        () -> {
          var check =
              Optional.ofNullable(checks.checks(c.tenantId(), List.of(checkId)).get(checkId))
                  .orElseThrow(() -> missing("PREGNANCY_CHECK"));
          if (!checks.invalidated(c.tenantId(), List.of(checkId)).isEmpty())
            throw conflict("PREGNANCY_CHECK_ALREADY_INVALIDATED", "Check is already invalidated");
          var reason = requiredText(input.reason(), 500);
          var now = now();
          var invalidation =
              new PregnancyCheckStore.Invalidation(
                  ids.next(), check.transferId(), check.id(), null, reason, c.actorId(), now);
          checks.invalidateAll(c.tenantId(), List.of(invalidation));
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "INVALIDATE",
                  "PREGNANCY_CHECK",
                  check.id(),
                  null,
                  reason,
                  check.result().name(),
                  "INVALIDATED"));
          return new InvalidationView(
              invalidation.id(), check.id(), check.transferId(), reason, c.actorId(), now);
        });
  }

  @Transactional(readOnly = true)
  public LatestOutcome latest(ExecutionContext c, UUID transferId) {
    access.require(c, "transfer:read");
    requireTransfer(c.tenantId(), transferId);
    return new LatestOutcome(transferId, checks.latest(c.tenantId(), transferId));
  }

  @Transactional(readOnly = true)
  public PageResult<PregnancyCheckStore.HistoryEntry> history(
      ExecutionContext c, UUID transferId, SearchPage page) {
    access.require(c, "transfer:read");
    requireTransfer(c.tenantId(), transferId);
    return new PageResult<>(
        checks.history(c.tenantId(), transferId, page.size(), page.offset()),
        page.page(),
        page.size());
  }

  @Transactional(readOnly = true)
  public PageResult<PregnancyCheckStore.FollowUp> followUps(
      ExecutionContext c, OutcomeWindows.Cohort cohort, LocalDate asOf, SearchPage page) {
    access.require(c, "transfer:read");
    if (cohort == null || asOf == null)
      throw rejected("OUTCOME_COHORT_REQUIRED", "Cohort and analysis date are required");
    var window = windows.cohort(cohort);
    return new PageResult<>(
        checks.followUps(c.tenantId(), cohort, window, asOf, page.size(), page.offset()),
        page.page(),
        page.size());
  }

  private EmbryoTransfer requireTransfer(UUID tenant, UUID id) {
    return transfers.transfer(tenant, id).orElseThrow(() -> missing("EMBRYO_TRANSFER"));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static String requiredText(String text, int max) {
    if (text == null || text.isBlank() || text.length() > max)
      throw rejected("CHECK_INVALIDATION_REASON_REQUIRED", "An invalidation reason is required");
    return text.strip();
  }

  private static ApplicationFailure missing(String subject) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, subject + "_NOT_FOUND", "Outcome fact not found");
  }

  private static ApplicationFailure rejected(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, message);
  }

  private static ApplicationFailure conflict(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, code, message);
  }

  public record Invalidate(String reason) {}

  public record InvalidationView(
      UUID id, UUID checkId, UUID transferId, String reason, UUID actorId, Instant recordedAt) {}

  public record LatestOutcome(UUID transferId, PregnancyCheck latestValidCheck) {}

  private record InvalidationIntent(UUID checkId, Invalidate input) {}
}
