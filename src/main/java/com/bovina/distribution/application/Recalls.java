package com.bovina.distribution.application;

import com.bovina.audit.application.*;
import com.bovina.cryostorage.application.ShipmentInventory;
import com.bovina.distribution.infrastructure.RecallStore;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Recalls {
  private static final Set<String> TRIGGERS =
      Set.of(
          "DONOR",
          "SEMEN_BATCH",
          "MATING",
          "PACKAGE",
          "OOCYTE_COLLECTION",
          "CRYOPRESERVATION_EVENT");
  private final TenantAccess access;
  private final RecallStore store;
  private final ShipmentInventory inventory;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public Recalls(
      TenantAccess access,
      RecallStore store,
      ShipmentInventory inventory,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.store = store;
    this.inventory = inventory;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public CaseView open(ExecutionContext c, UUID key, Open input) {
    access.require(c, "recall:write");
    if (!key.equals(input.id())) throw rejected("RECALL_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "OPEN_LINEAGE_RECALL_V1",
        input,
        CaseView.class,
        () -> {
          store.requireTrigger(c.tenantId(), input.triggerType(), input.triggerId());
          if (input.sourceDocumentId() != null)
            documents.requireReference(c.tenantId(), input.sourceDocumentId());
          store.insert(c, input, now());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now(),
                  "OPEN",
                  "RECALL_CASE",
                  input.id(),
                  null,
                  input.reason(),
                  null,
                  "IMPACT_AND_SEGREGATION_ONLY"));
          return find(c, input.id());
        });
  }

  @Transactional(readOnly = true)
  public CaseView get(ExecutionContext c, UUID id) {
    access.require(c, "recall:read");
    return find(c, id);
  }

  @Transactional(readOnly = true)
  public PageResult<CaseView> page(ExecutionContext c, SearchPage page) {
    access.require(c, "recall:read");
    return new PageResult<>(store.page(c.tenantId(), page), page.page(), page.size());
  }

  @Transactional(readOnly = true)
  public Impact analyze(ExecutionContext c, UUID id, Analysis input) {
    access.require(c, "recall:read");
    var recall = find(c, id);
    var cutoff = cutoff(recall, input.cutoff());
    var page = new SearchPage(null, input.page(), input.size());
    return new Impact(
        id, cutoff, store.impact(c.tenantId(), recall, cutoff, page), page.page(), page.size());
  }

  @Transactional
  public HoldExecution placeHolds(ExecutionContext c, UUID key, UUID id, HoldCommand input) {
    access.require(c, "recall:write");
    if (!key.equals(input.id())) throw rejected("RECALL_EXECUTION_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "SEGREGATE_LINEAGE_RECALL_V1",
        new HoldIntent(id, input),
        HoldExecution.class,
        () -> {
          var recall = find(c, id);
          var cutoff = cutoff(recall, input.cutoff());
          var packages =
              input.items().stream().map(ShipmentInventory.HoldIntent::packageId).toList();
          var affected = store.affectedMembers(c.tenantId(), recall, cutoff, packages);
          if (!affected.keySet().equals(new HashSet<>(packages)))
            throw rejected("PACKAGE_OUTSIDE_RECALL_IMPACT");
          var results = inventory.placeRecallHolds(c, input.items(), recall.reason(), affected);
          store.holdExecution(c, input.id(), id, cutoff, results, now());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now(),
                  "SEGREGATE",
                  "RECALL_CASE",
                  id,
                  null,
                  recall.reason(),
                  null,
                  "EXECUTION=" + input.id()));
          return new HoldExecution(input.id(), cutoff, results);
        });
  }

  @Transactional(readOnly = true)
  public HoldExecution execution(ExecutionContext c, UUID id, UUID execution) {
    access.require(c, "recall:read");
    find(c, id);
    var result = store.execution(c.tenantId(), id, execution);
    if (result == null) throw missing();
    return result;
  }

  private CaseView find(ExecutionContext c, UUID id) {
    var result = store.find(c.tenantId(), id);
    if (result == null) throw missing();
    return result;
  }

  private Instant cutoff(CaseView recall, Instant value) {
    var now = now();
    var chosen = value == null ? now : value.truncatedTo(ChronoUnit.MICROS);
    if (chosen.isAfter(now) || chosen.isBefore(recall.openedAt()))
      throw rejected("INVALID_RECALL_ANALYSIS_CUTOFF");
    return chosen;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, "Invalid recall request");
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "RECALL_NOT_FOUND", "Recall or execution not found");
  }

  public record Open(
      UUID id, String triggerType, UUID triggerId, String reason, UUID sourceDocumentId) {
    public Open {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(triggerId);
      if (triggerType == null
          || !TRIGGERS.contains(triggerType)
          || reason == null
          || reason.isBlank()
          || reason.length() > 500) throw rejected("INVALID_RECALL_CRITERION");
    }
  }

  public record Analysis(Instant cutoff, int page, int size) {}

  public record HoldCommand(UUID id, Instant cutoff, List<ShipmentInventory.HoldIntent> items) {
    public HoldCommand {
      StableIds.requireVersion7(id);
      if (cutoff == null || items == null || items.isEmpty() || items.size() > 100)
        throw rejected("INVALID_RECALL_HOLD_BATCH");
      items = List.copyOf(items);
      if (new HashSet<>(items.stream().map(ShipmentInventory.HoldIntent::packageId).toList()).size()
              != items.size()
          || new HashSet<>(items.stream().map(ShipmentInventory.HoldIntent::id).toList()).size()
              != items.size()) throw rejected("DUPLICATE_RECALL_HOLD_ITEM");
      for (var i : items) {
        StableIds.requireVersion7(i.id());
        StableIds.requireVersion7(i.packageId());
        if (i.expectedVersion() < 0) throw rejected("INVALID_PACKAGE_VERSION");
      }
    }
  }

  public record CaseView(
      UUID id,
      String triggerType,
      UUID triggerId,
      String reason,
      Instant openedAt,
      UUID sourceDocumentId) {}

  public record MaterialImpact(
      UUID embryoId,
      UUID matingId,
      UUID donorId,
      UUID semenBatchId,
      UUID sireId,
      String availability,
      UUID packageId,
      boolean activeMembership,
      UUID currentLocation,
      Long packageVersion,
      UUID locationAtCutoff,
      UUID shipmentId,
      String destinationName,
      String destinationPropertyName,
      String destinationAddressLine,
      String destinationMunicipality,
      String destinationState,
      String destinationCountry,
      Instant dispatchedAt,
      UUID transferId,
      boolean held) {}

  public record Impact(
      UUID recallId, Instant analysisCutoff, List<MaterialImpact> materials, int page, int size) {}

  public record HoldExecution(
      UUID id, Instant analysisCutoff, List<ShipmentInventory.HoldResult> items) {
    public HoldExecution {
      items = List.copyOf(items);
    }
  }

  private record HoldIntent(UUID recallId, HoldCommand input) {}
}
