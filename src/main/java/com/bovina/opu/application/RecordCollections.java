package com.bovina.opu.application;

import com.bovina.animals.application.DonorDirectory;
import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.domain.*;
import com.bovina.opu.infrastructure.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.platform.infrastructure.CommandReceiptStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordCollections {
  private final TenantAccess access;
  private final OpuSessionRepository sessions;
  private final OpuFacts facts;
  private final DonorDirectory donors;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final CommandReceiptStore hashes;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public RecordCollections(
      TenantAccess access,
      OpuSessionRepository sessions,
      OpuFacts facts,
      DonorDirectory donors,
      DocumentReferences documents,
      CommandReceipts receipts,
      CommandReceiptStore hashes,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.sessions = sessions;
    this.facts = facts;
    this.donors = donors;
    this.documents = documents;
    this.receipts = receipts;
    this.hashes = hashes;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public CollectionBatch.Result preview(ExecutionContext c, UUID session, CollectionBatch batch) {
    access.require(c, "opu:write");
    requireSession(c, session, batch.expectedSessionVersion());
    return new CollectionBatch.Result(batch.batchId(), true, validate(c, session, batch));
  }

  @Transactional
  public CollectionBatch.Result record(
      ExecutionContext c, UUID key, UUID session, CollectionBatch batch) {
    access.require(c, "opu:write");
    if (!batch.batchId().equals(key))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "BATCH_KEY_MISMATCH",
          "Idempotency key must equal batch ID");
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_COLLECTIONS_V1",
        new Intent(session, batch),
        CollectionBatch.Result.class,
        () -> {
          requireSession(c, session, batch.expectedSessionVersion());
          var results = validate(c, session, batch);
          if (results.stream().anyMatch(r -> r.errorCode() != null))
            throw new ApplicationFailure(
                ApplicationFailure.Kind.REJECTED,
                "COLLECTION_BATCH_REJECTED",
                "No collections were written; use dry-run for per-item errors");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          if (batch.source().origin() == DataProvenance.Origin.IMPORT)
            facts.registerImport(
                c,
                batch.batchId(),
                batch.source().sourceDocumentId(),
                hashes.hash(c.actorId(), "OPU_IMPORT_V1", batch),
                now);
          var records =
              batch.items().stream()
                  .map(
                      i ->
                          new OocyteCollection(
                              c.tenantId(),
                              session,
                              i.registration(),
                              batch.source().provenance(c, now, batch.batchId())))
                  .toList();
          facts.insert(c.tenantId(), records);
          audit.recordAll(
              records.stream()
                  .map(
                      r ->
                          new AuditEvent(
                              ids.next(),
                              c,
                              now,
                              "RECORD",
                              "OOCYTE_COLLECTION",
                              r.id(),
                              0L,
                              null,
                              null,
                              "RECORDED"))
                  .toList());
          return new CollectionBatch.Result(
              batch.batchId(),
              false,
              batch.items().stream()
                  .map(i -> new CollectionBatch.ItemResult(i.itemId(), i.id(), "APPLIED", null))
                  .toList());
        });
  }

  private void requireSession(ExecutionContext c, UUID id, long version) {
    var s = sessions.lock(c.tenantId(), id).orElseThrow(OpuSessions::missing);
    s.requireVersion(version);
    s.requireWritable();
  }

  private List<CollectionBatch.ItemResult> validate(
      ExecutionContext c, UUID session, CollectionBatch batch) {
    if (batch.source().sourceDocumentId() != null)
      documents.requireReference(c.tenantId(), batch.source().sourceDocumentId());
    var donorIds =
        batch.items().stream()
            .map(CollectionBatch.Item::donorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    var identities = donors.snapshots(c.tenantId(), donorIds);
    var usedDonors = facts.donors(c.tenantId(), session);
    var seenIds = new HashSet<UUID>();
    var result = new ArrayList<CollectionBatch.ItemResult>();
    for (var item : batch.items()) {
      String error = null;
      try {
        item.registration();
        if (!seenIds.add(item.id())) error = "DUPLICATE_COLLECTION_ID";
        else if (!usedDonors.add(item.donorId())) error = "DUPLICATE_SESSION_DONOR";
        else if (!identities.containsKey(item.donorId())) error = "DONOR_NOT_FOUND";
        else if (!identities.get(item.donorId()).eligibleForNewCollection())
          error = "DONOR_NOT_ELIGIBLE";
      } catch (ApplicationFailure failure) {
        error = failure.code();
      }
      result.add(
          new CollectionBatch.ItemResult(
              item.itemId(),
              error == null ? item.id() : null,
              error == null ? "VALID" : "REJECTED",
              error));
    }
    return List.copyOf(result);
  }

  private record Intent(UUID session, CollectionBatch batch) {}
}
