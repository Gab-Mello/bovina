package com.bovina.embryology.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.embryology.domain.*;
import com.bovina.embryology.infrastructure.*;
import com.bovina.fertilization.application.Matings;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.CounterpartyAccess;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.platform.infrastructure.CommandReceiptStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Embryos {
  private final TenantAccess access;
  private final EmbryoRepository repository;
  private final EmbryologyFacts facts;
  private final Matings matings;
  private final CounterpartyAccess parties;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final CommandReceiptStore hashes;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;
  private final PreservationFacts preservation;

  public Embryos(
      TenantAccess access,
      EmbryoRepository repository,
      EmbryologyFacts facts,
      Matings matings,
      CounterpartyAccess parties,
      DocumentReferences documents,
      CommandReceipts receipts,
      CommandReceiptStore hashes,
      AuditRecorder audit,
      StableIds ids,
      Clock clock,
      PreservationFacts preservation) {
    this.access = access;
    this.repository = repository;
    this.facts = facts;
    this.matings = matings;
    this.parties = parties;
    this.documents = documents;
    this.receipts = receipts;
    this.hashes = hashes;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
    this.preservation = preservation;
  }

  @Transactional
  public EmbryoBatch.Result identify(ExecutionContext c, UUID key, EmbryoBatch batch) {
    access.require(c, "embryology:write");
    if (!batch.batchId().equals(key))
      throw rejected("BATCH_KEY_MISMATCH", "Idempotency key must equal batch ID");
    return receipts.replayOrExecute(
        c,
        key,
        "IDENTIFY_EMBRYOS_V1",
        batch,
        EmbryoBatch.Result.class,
        () -> identifyOnce(c, batch));
  }

  private EmbryoBatch.Result identifyOnce(ExecutionContext c, EmbryoBatch batch) {
    var now = now();
    if (batch.source().sourceDocumentId() != null)
      documents.requireReference(c.tenantId(), batch.source().sourceDocumentId());
    if (batch.source().origin() == DataProvenance.Origin.IMPORT)
      facts.registerImport(
          c,
          batch.batchId(),
          "EMBRYOS",
          batch.source().sourceDocumentId(),
          hashes.hash(c.actorId(), "EMBRYO_IMPORT_V1", batch),
          now);
    batch.items().stream()
        .map(EmbryoBatch.Item::ownerId)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(id -> parties.requireActive(c.tenantId(), id));
    var matingById = new HashMap<UUID, Matings.EmbryologyCapacity>();
    batch.items().stream()
        .map(EmbryoBatch.Item::matingId)
        .distinct()
        .sorted()
        .forEach(id -> matingById.put(id, matings.lockForEmbryology(c.tenantId(), id)));
    var requested = new HashMap<UUID, Long>();
    for (var item : batch.items()) requested.merge(item.matingId(), 1L, Long::sum);
    requested.forEach(
        (id, count) -> {
          var total = facts.embryoCount(c.tenantId(), id) + count;
          if (total > matingById.get(id).allocatedOocytes())
            throw rejected(
                "EMBRYO_COUNT_EXCEEDS_ALLOCATION", "Individual embryos exceed allocated oocytes");
        });
    var embryos =
        batch.items().stream()
            .map(
                i ->
                    new Embryo(
                        c.tenantId(),
                        i.registration(),
                        batch.source().provenance(c, now, batch.batchId())))
            .toList();
    repository.saveAll(embryos);
    repository.flush();
    audit.recordAll(
        embryos.stream()
            .map(
                e ->
                    new AuditEvent(
                        ids.next(),
                        c,
                        now,
                        "IDENTIFY",
                        "EMBRYO",
                        e.id(),
                        0L,
                        null,
                        null,
                        "AVAILABLE"))
            .toList());
    return new EmbryoBatch.Result(
        batch.batchId(),
        batch.items().stream()
            .map(i -> new EmbryoBatch.ItemResult(i.itemId(), i.id(), "APPLIED"))
            .toList());
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "embryology:read");
    return view(c.tenantId(), find(c.tenantId(), id, false));
  }

  @Transactional(readOnly = true)
  public PageResult<View> search(ExecutionContext c, UUID mating, SearchPage page) {
    access.require(c, "embryology:read");
    var rows =
        repository.page(
            c.tenantId(), mating, page.pattern(), PageRequest.of(page.page(), page.size()));
    var embryoIds = rows.stream().map(Embryo::id).toList();
    var states = preservation.states(c.tenantId(), embryoIds);
    var holds = facts.holds(c.tenantId(), embryoIds);
    var evaluations = facts.currentEvaluations(c.tenantId(), embryoIds);
    return new PageResult<>(
        rows.stream()
            .map(
                e -> {
                  var state = states.get(e.id());
                  return new View(
                      e.id(),
                      e.matingId(),
                      e.humanCode(),
                      e.ownerId(),
                      e.identifiedAt(),
                      e.availability().name(),
                      state.preservation(),
                      holds.getOrDefault(e.id(), List.of()),
                      state.currentLocationId(),
                      evaluations.get(e.id()),
                      e.version(),
                      e.provenance());
                })
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Lineage lineage(UUID tenant, UUID id) {
    var embryo = find(tenant, id, false);
    return new Lineage(embryo.id(), embryo.matingId(), matings.lineage(tenant, embryo.matingId()));
  }

  @Transactional
  public View transition(
      ExecutionContext c, UUID key, UUID id, long expectedVersion, Action action, String reason) {
    access.require(c, "embryology:write");
    return receipts.replayOrExecute(
        c,
        key,
        "EMBRYO_" + action.name() + "_V1",
        new TransitionIntent(id, expectedVersion, action, reason),
        View.class,
        () -> {
          var embryo = find(c.tenantId(), id, true);
          if (embryo.version() != expectedVersion)
            throw new ApplicationFailure(
                ApplicationFailure.Kind.CONFLICT,
                "STALE_EMBRYO_VERSION",
                "Embryo version has changed");
          if (facts.hasActiveHold(c.tenantId(), id))
            throw new ApplicationFailure(
                ApplicationFailure.Kind.CONFLICT,
                "EMBRYO_ON_HOLD",
                "Release active holds before changing availability");
          var before = embryo.availability().name();
          switch (action) {
            case DISCARD -> embryo.discard();
          }
          repository.flush();
          var now = now();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  action.name(),
                  "EMBRYO",
                  id,
                  embryo.version(),
                  reason,
                  before,
                  embryo.availability().name()));
          return view(c.tenantId(), embryo);
        });
  }

  @Transactional
  public EmbryologyFacts.Hold openHold(
      ExecutionContext c, UUID key, UUID embryoId, OpenHold input) {
    access.require(c, "embryology:write");
    return receipts.replayOrExecute(
        c,
        key,
        "OPEN_EMBRYO_HOLD_V1",
        new HoldIntent(embryoId, input),
        EmbryologyFacts.Hold.class,
        () -> {
          find(c.tenantId(), embryoId, true);
          var type = code(input.type());
          var reason = text(input.reason(), 500, "HOLD_REASON_REQUIRED");
          var now = now();
          var hold =
              facts.openHold(c.tenantId(), input.id(), embryoId, type, reason, c.actorId(), now);
          audit.record(
              new AuditEvent(
                  ids.next(), c, now, "OPEN_HOLD", "EMBRYO", embryoId, null, reason, null, type));
          return hold;
        });
  }

  @Transactional
  public EmbryologyFacts.Hold releaseHold(
      ExecutionContext c, UUID key, UUID embryoId, UUID holdId, String reason) {
    access.require(c, "embryology:write");
    return receipts.replayOrExecute(
        c,
        key,
        "RELEASE_EMBRYO_HOLD_V1",
        new ReleaseHoldIntent(embryoId, holdId, reason),
        EmbryologyFacts.Hold.class,
        () -> {
          find(c.tenantId(), embryoId, true);
          var safe = text(reason, 500, "HOLD_RELEASE_REASON_REQUIRED");
          var now = now();
          var hold = facts.releaseHold(c.tenantId(), holdId, safe, c.actorId(), now);
          if (!hold.embryoId().equals(embryoId))
            throw rejected("EMBRYO_HOLD_MISMATCH", "Hold does not belong to embryo");
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "RELEASE_HOLD",
                  "EMBRYO",
                  embryoId,
                  null,
                  safe,
                  hold.type(),
                  "RELEASED"));
          return hold;
        });
  }

  private View view(UUID tenant, Embryo e) {
    var material = preservation.state(tenant, e.id());
    return new View(
        e.id(),
        e.matingId(),
        e.humanCode(),
        e.ownerId(),
        e.identifiedAt(),
        e.availability().name(),
        material.preservation(),
        facts.holds(tenant, e.id()),
        material.currentLocationId(),
        facts.currentEvaluation(tenant, e.id()),
        e.version(),
        e.provenance());
  }

  private Embryo find(UUID tenant, UUID id, boolean lock) {
    return (lock ? repository.lock(tenant, id) : repository.findByOrganizationIdAndId(tenant, id))
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND, "EMBRYO_NOT_FOUND", "Embryo not found"));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static String code(String value) {
    value = text(value, 48, "INVALID_HOLD_TYPE").toUpperCase(Locale.ROOT);
    if (!value.matches("[A-Z][A-Z0-9_]{0,47}"))
      throw rejected("INVALID_HOLD_TYPE", "Hold type is invalid");
    return value;
  }

  private static String text(String value, int max, String code) {
    if (value == null || value.isBlank() || value.length() > max)
      throw rejected(code, "Required text is invalid");
    return value.strip();
  }

  private static ApplicationFailure rejected(String code, String detail) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, detail);
  }

  public enum Action {
    DISCARD
  }

  public record OpenHold(UUID id, String type, String reason) {
    public OpenHold {
      StableIds.requireVersion7(id);
    }
  }

  public record View(
      UUID id,
      UUID matingId,
      String humanCode,
      UUID ownerId,
      Instant identifiedAt,
      String availability,
      String preservation,
      List<EmbryologyFacts.Hold> holds,
      UUID currentLocationId,
      EmbryoEvaluation currentEvaluation,
      long version,
      DataProvenance provenance) {
    public View {
      holds = List.copyOf(holds);
    }
  }

  public record Lineage(
      UUID embryoId,
      UUID matingId,
      com.bovina.fertilization.application.MatingLineageSnapshot mating) {}

  private record TransitionIntent(UUID id, long version, Action action, String reason) {}

  private record HoldIntent(UUID embryoId, OpenHold hold) {}

  private record ReleaseHoldIntent(UUID embryoId, UUID holdId, String reason) {}
}
