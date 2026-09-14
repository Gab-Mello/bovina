package com.bovina.transfer.application;

import com.bovina.animals.application.RecipientDirectory;
import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.protocols.application.Protocols;
import com.bovina.transfer.domain.RecipientCycle;
import com.bovina.transfer.infrastructure.RecipientCycleRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RecipientCycles {
  private final TenantAccess access;
  private final RecipientDirectory recipients;
  private final Protocols protocols;
  private final RecipientCycleRepository repository;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public RecipientCycles(
      TenantAccess access,
      RecipientDirectory recipients,
      Protocols protocols,
      RecipientCycleRepository repository,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.recipients = recipients;
    this.protocols = protocols;
    this.repository = repository;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public View open(ExecutionContext c, UUID key, RecipientCycle.Registration input) {
    access.require(c, "transfer:write");
    return receipts.replayOrExecute(
        c,
        key,
        "OPEN_RECIPIENT_CYCLE_V1",
        input,
        View.class,
        () -> {
          var recipient = recipients.requireActive(c.tenantId(), input.recipientAnimalId());
          if (input.protocolVersionId() != null)
            protocols.requireAvailableVersion(c.tenantId(), input.protocolVersionId());
          var now = now();
          var cycle =
              repository.save(
                  new RecipientCycle(c.tenantId(), input, DataProvenance.manual(c.actorId(), now)));
          repository.flush();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "OPEN",
                  "RECIPIENT_CYCLE",
                  cycle.id(),
                  0L,
                  null,
                  null,
                  "OPEN"));
          return view(cycle, recipient.name());
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "transfer:read");
    var cycle = find(c.tenantId(), id, false);
    var recipient = recipients.snapshots(c.tenantId(), List.of(cycle.recipientAnimalId()));
    return view(cycle, recipient.get(cycle.recipientAnimalId()).name());
  }

  @Transactional(readOnly = true)
  public PageResult<View> search(
      ExecutionContext c, UUID recipient, RecipientCycle.Status status, SearchPage page) {
    access.require(c, "transfer:read");
    var cycles =
        repository.page(c.tenantId(), recipient, status, PageRequest.of(page.page(), page.size()));
    var recipientIds =
        cycles.stream()
            .map(RecipientCycle::recipientAnimalId)
            .collect(java.util.stream.Collectors.toSet());
    var names = recipients.snapshots(c.tenantId(), recipientIds);
    var views = new ArrayList<View>(cycles.size());
    for (var cycle : cycles) views.add(view(cycle, names.get(cycle.recipientAnimalId()).name()));
    return new PageResult<>(views, page.page(), page.size());
  }

  @Transactional
  public View close(ExecutionContext c, UUID key, UUID id, Close input) {
    access.require(c, "transfer:write");
    return receipts.replayOrExecute(
        c,
        key,
        "CLOSE_RECIPIENT_CYCLE_V1",
        new CloseIntent(id, input),
        View.class,
        () -> {
          var cycle = find(c.tenantId(), id, true);
          cycle.close(input.expectedVersion(), input.closedOn(), input.reason());
          repository.flush();
          var now = now();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "CLOSE",
                  "RECIPIENT_CYCLE",
                  id,
                  cycle.version(),
                  input.reason(),
                  "OPEN",
                  "CLOSED"));
          return view(cycle, null);
        });
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, RecipientCycle> lockOpen(UUID tenant, Collection<UUID> ids) {
    var distinct = new TreeSet<>(ids);
    var cycles = repository.lockAll(tenant, distinct);
    if (cycles.size() != distinct.size()) throw missing();
    var result = new HashMap<UUID, RecipientCycle>();
    for (var cycle : cycles) {
      cycle.requireOpen();
      result.put(cycle.id(), cycle);
    }
    return Map.copyOf(result);
  }

  private RecipientCycle find(UUID tenant, UUID id, boolean lock) {
    return (lock ? repository.lock(tenant, id) : repository.findByOrganizationIdAndId(tenant, id))
        .orElseThrow(RecipientCycles::missing);
  }

  private static View view(RecipientCycle cycle, String recipientName) {
    return new View(
        cycle.id(),
        cycle.recipientAnimalId(),
        recipientName,
        cycle.protocolVersionId(),
        cycle.openedOn(),
        cycle.closedOn(),
        cycle.status().name(),
        cycle.notes(),
        cycle.closureReason(),
        cycle.version(),
        cycle.provenance());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND,
        "RECIPIENT_CYCLE_NOT_FOUND",
        "Recipient cycle not found");
  }

  private static ApplicationFailure rejected(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, message);
  }

  public record Close(long expectedVersion, LocalDate closedOn, String reason) {}

  public record View(
      UUID id,
      UUID recipientAnimalId,
      String recipientName,
      UUID protocolVersionId,
      LocalDate openedOn,
      LocalDate closedOn,
      String status,
      String notes,
      String closureReason,
      long version,
      DataProvenance provenance) {}

  private record CloseIntent(UUID cycleId, Close input) {}
}
