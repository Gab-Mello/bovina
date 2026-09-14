package com.bovina.fertilization.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.fertilization.domain.*;
import com.bovina.fertilization.infrastructure.MatingStore;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.opu.application.CollectionAllocationBoundary;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.platform.infrastructure.CommandReceiptStore;
import com.bovina.semen.application.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Matings {
  private final TenantAccess access;
  private final CollectionAllocationBoundary collections;
  private final SemenBatches semen;
  private final OpuFacilities facilities;
  private final DocumentReferences documents;
  private final MatingStore store;
  private final CommandReceipts receipts;
  private final CommandReceiptStore hashes;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public Matings(
      TenantAccess access,
      CollectionAllocationBoundary collections,
      SemenBatches semen,
      OpuFacilities facilities,
      DocumentReferences documents,
      MatingStore store,
      CommandReceipts receipts,
      CommandReceiptStore hashes,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.collections = collections;
    this.semen = semen;
    this.facilities = facilities;
    this.documents = documents;
    this.store = store;
    this.receipts = receipts;
    this.hashes = hashes;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public MatingBatch.Result allocate(ExecutionContext c, UUID key, MatingBatch batch) {
    access.require(c, "fertilization:write");
    if (!batch.batchId().equals(key))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "BATCH_KEY_MISMATCH",
          "Idempotency key must equal batch ID");
    return receipts.replayOrExecute(
        c,
        key,
        "ALLOCATE_MATINGS_V1",
        batch,
        MatingBatch.Result.class,
        () -> allocateOnce(c, batch));
  }

  private MatingBatch.Result allocateOnce(ExecutionContext c, MatingBatch batch) {
    var now = now();
    if (batch.source().sourceDocumentId() != null)
      documents.requireReference(c.tenantId(), batch.source().sourceDocumentId());
    if (batch.source().origin() == DataProvenance.Origin.IMPORT)
      store.registerImport(
          c,
          batch.batchId(),
          batch.source().sourceDocumentId(),
          hashes.hash(c.actorId(), "MATING_IMPORT_V1", batch),
          now);

    var capacities = new HashMap<UUID, CollectionAllocationBoundary.Capacity>();
    batch.items().stream()
        .map(MatingBatch.Item::collectionId)
        .distinct()
        .sorted()
        .forEach(id -> capacities.put(id, collections.lockCompleted(c, id)));

    var lineages = new HashMap<UUID, SemenLineage>();
    batch.items().stream()
        .map(MatingBatch.Item::semenBatchId)
        .distinct()
        .sorted()
        .forEach(id -> lineages.put(id, semen.requireLineage(c.tenantId(), id)));

    batch.items().stream()
        .map(MatingBatch.Item::responsibleProfessionalId)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(id -> facilities.requireProfessional(c.tenantId(), id));

    var allocated = new HashMap<UUID, Long>();
    capacities.keySet().forEach(id -> allocated.put(id, store.allocated(c.tenantId(), id)));
    var matings = new ArrayList<Mating>();
    var snapshots = new HashMap<UUID, MatingLineageSnapshot>();
    for (var item : batch.items()) {
      var capacity = capacities.get(item.collectionId());
      var mating = item.mating(batch.source().provenance(c, now, batch.batchId()));
      if (mating.fertilizedAt().isBefore(capacity.collectedAt()))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "FERTILIZATION_PRECEDES_COLLECTION",
            "Fertilization cannot precede oocyte collection");
      var current = allocated.get(item.collectionId());
      capacity.requireAllocation(current, mating.allocatedOocytes());
      allocated.put(item.collectionId(), current + mating.allocatedOocytes());
      matings.add(mating);
      snapshots.put(
          mating.id(),
          new MatingLineageSnapshot(
              capacity.collectionId(), capacity.donorId(), lineages.get(mating.semenBatchId())));
    }
    store.insertAll(c.tenantId(), matings, snapshots);
    audit.recordAll(
        matings.stream()
            .map(
                m ->
                    new AuditEvent(
                        ids.next(),
                        c,
                        now,
                        "ALLOCATE",
                        "MATING",
                        m.id(),
                        0L,
                        null,
                        null,
                        "FERTILIZED"))
            .toList());
    return new MatingBatch.Result(
        batch.batchId(),
        batch.items().stream()
            .map(i -> new MatingBatch.ItemResult(i.itemId(), i.id(), "APPLIED"))
            .toList());
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "fertilization:read");
    var mating = find(c.tenantId(), id, false);
    return new View(mating, store.snapshot(c.tenantId(), id));
  }

  @Transactional(readOnly = true)
  public PageResult<Mating> search(
      ExecutionContext c, UUID collection, UUID semenBatch, SearchPage page) {
    access.require(c, "fertilization:read");
    return new PageResult<>(
        store.page(c.tenantId(), collection, semenBatch, page.size(), page.offset()),
        page.page(),
        page.size());
  }

  @Transactional
  public CorrectionView requestCorrection(
      ExecutionContext c, UUID key, UUID matingId, Correction request) {
    access.require(c, "lineage:correct");
    return receipts.replayOrExecute(
        c,
        key,
        "REQUEST_MATING_CORRECTION_V1",
        new CorrectionIntent(matingId, request),
        CorrectionView.class,
        () -> {
          var mating = find(c.tenantId(), matingId, true);
          if (request.reason() == null
              || request.reason().isBlank()
              || request.reason().length() > 500)
            throw new ApplicationFailure(
                ApplicationFailure.Kind.REJECTED,
                "CORRECTION_REASON_REQUIRED",
                "A correction reason is required");
          if (request.proposedSemenBatchId() != null)
            semen.requireLineage(c.tenantId(), request.proposedSemenBatchId());
          var id = ids.next();
          var now = now();
          store.requestCorrection(
              c.tenantId(), id, mating.id(), request.reason(), request, c.actorId(), now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "CORRECT",
                  "MATING",
                  mating.id(),
                  mating.version(),
                  request.reason(),
                  null,
                  "REQUESTED"));
          return new CorrectionView(id, mating.id(), "REQUESTED", now);
        });
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public EmbryologyCapacity lockForEmbryology(UUID tenant, UUID id) {
    var mating = find(tenant, id, true);
    mating.requireEmbryologyOpen();
    return new EmbryologyCapacity(mating.id(), mating.allocatedOocytes(), mating.version());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void completeEmbryology(UUID tenant, UUID id, long expectedVersion) {
    store.complete(tenant, id, expectedVersion);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public MatingLineageSnapshot lineage(UUID tenant, UUID id) {
    find(tenant, id, false);
    return store.snapshot(tenant, id);
  }

  private Mating find(UUID tenant, UUID id, boolean lock) {
    return store
        .find(tenant, id, lock)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND, "MATING_NOT_FOUND", "Mating not found"));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  public record View(Mating mating, MatingLineageSnapshot lineage) {}

  public record Correction(
      UUID proposedSemenBatchId, Instant proposedFertilizedAt, String reason) {}

  public record CorrectionView(UUID id, UUID matingId, String status, Instant requestedAt) {}

  public record EmbryologyCapacity(UUID matingId, int allocatedOocytes, long version) {}

  private record CorrectionIntent(UUID matingId, Correction correction) {}
}
