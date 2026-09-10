package com.bovina.parties.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.domain.*;
import com.bovina.parties.domain.ClientImportBatch.*;
import com.bovina.parties.infrastructure.ClientImportStore;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientImportTransactions {
  private final TenantAccess access;
  private final ClientImportStore store;
  private final DocumentReferences documents;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public ClientImportTransactions(
      TenantAccess access,
      ClientImportStore store,
      DocumentReferences documents,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.store = store;
    this.documents = documents;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public void bind(ExecutionContext c, ClientImportBatch batch) {
    access.require(c, "master-data:write");
    if (batch.sourceDocumentId() != null)
      documents.requireReference(c.tenantId(), batch.sourceDocumentId());
    var hash = store.fingerprint(c, batch);
    if (store.bind(c, batch, hash, clock.instant()))
      audit.record(
          new AuditEvent(
              ids.next(),
              c,
              clock.instant(),
              "REGISTER",
              "IMPORT_BATCH",
              batch.batchId(),
              null,
              null,
              null,
              batch.mode().name()));
    if (!hash.equals(store.storedHash(c.tenantId(), batch.batchId())))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Import batch ID belongs to a different payload or actor");
  }

  @Transactional
  public Result atomic(ExecutionContext c, ClientImportBatch batch) {
    access.require(c, "master-data:write");
    batch.requireMode(Mode.ATOMIC);
    lockIntent(c, batch);
    var previous = store.results(c.tenantId(), batch.batchId());
    if (!previous.isEmpty()) return result(batch, previous);
    var validation = batch.validate(store.existingClients(c.tenantId(), batch));
    if (validation.stream().anyMatch(r -> r.status() != ItemStatus.VALID)) {
      var rejected =
          validation.stream()
              .map(
                  r ->
                      r.status() == ItemStatus.VALID
                          ? new ItemResult(
                              r.itemId(), null, ItemStatus.NOT_APPLIED, "ATOMIC_BATCH_REJECTED")
                          : r)
              .toList();
      store.recordResults(c.tenantId(), batch.batchId(), rejected);
      return result(batch, rejected);
    }
    var applied = insertClients(c, batch, batch.items());
    store.recordResults(c.tenantId(), batch.batchId(), applied);
    return result(batch, applied);
  }

  @Transactional
  public ItemResult partialItem(ExecutionContext c, ClientImportBatch batch, Row row) {
    access.require(c, "master-data:write");
    batch.requireMode(Mode.PARTIAL);
    lockIntent(c, batch);
    int index = batch.items().indexOf(row);
    if (index < 0)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "IMPORT_ITEM_MISMATCH",
          "Item does not belong to this batch");
    var validation = batch.validate(Set.of()).get(index);
    var previous = store.result(c.tenantId(), batch.batchId(), row.itemId());
    if (previous.isPresent()) return previous.orElseThrow();
    ItemResult result = validation;
    // PARTIAL deliberately commits per item; a constraint failure must roll back before recording
    // its result.
    if (validation.status() == ItemStatus.VALID)
      result = insertClients(c, batch, List.of(row)).getFirst();
    store.recordResults(c.tenantId(), batch.batchId(), List.of(result));
    return result;
  }

  @Transactional
  public ItemResult rejectItem(ExecutionContext c, ClientImportBatch batch, UUID item) {
    access.require(c, "master-data:write");
    batch.requireMode(Mode.PARTIAL);
    if (batch.items().stream().noneMatch(row -> row.itemId().equals(item)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "IMPORT_ITEM_MISMATCH",
          "Item does not belong to this batch");
    lockIntent(c, batch);
    var previous = store.result(c.tenantId(), batch.batchId(), item);
    if (previous.isPresent()) return previous.orElseThrow();
    var rejected = new ItemResult(item, null, ItemStatus.REJECTED, "CONSTRAINT_CONFLICT");
    store.recordResults(c.tenantId(), batch.batchId(), List.of(rejected));
    return rejected;
  }

  @Transactional
  public Result rejectAtomic(ExecutionContext c, ClientImportBatch batch) {
    access.require(c, "master-data:write");
    batch.requireMode(Mode.ATOMIC);
    lockIntent(c, batch);
    var previous = store.results(c.tenantId(), batch.batchId());
    if (!previous.isEmpty()) return result(batch, previous);
    var rejected =
        batch.items().stream()
            .map(
                r ->
                    new ItemResult(
                        r.itemId(), null, ItemStatus.NOT_APPLIED, "ATOMIC_CONSTRAINT_CONFLICT"))
            .toList();
    store.recordResults(c.tenantId(), batch.batchId(), rejected);
    return result(batch, rejected);
  }

  private List<ItemResult> insertClients(
      ExecutionContext c, ClientImportBatch batch, List<Row> rows) {
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var provenance =
        new DataProvenance(
            DataProvenance.Origin.IMPORT,
            batch.sourceDocumentId(),
            batch.batchId(),
            null,
            c.actorId(),
            now,
            null,
            null,
            null);
    var clients =
        rows.stream()
            .map(
                row ->
                    Party.registerClient(
                        row.id(),
                        c.tenantId(),
                        row.type(),
                        row.displayName(),
                        row.occurredAt(),
                        provenance))
            .toList();
    store.insertClients(clients);
    audit.recordAll(
        clients.stream()
            .map(
                p ->
                    new AuditEvent(
                        ids.next(), c, now, "IMPORT", "CLIENT", p.id(), 0L, null, null, "ACTIVE"))
            .toList());
    return rows.stream()
        .map(row -> new ItemResult(row.itemId(), row.id(), ItemStatus.APPLIED, null))
        .toList();
  }

  private void lockIntent(ExecutionContext c, ClientImportBatch batch) {
    store.lock(c.tenantId(), batch.batchId());
    if (!store.fingerprint(c, batch).equals(store.storedHash(c.tenantId(), batch.batchId())))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Import intent cannot change after registration");
  }

  private static Result result(ClientImportBatch batch, List<ItemResult> results) {
    var byItem = results.stream().collect(Collectors.toMap(ItemResult::itemId, r -> r));
    return new Result(
        batch.batchId(),
        batch.mode(),
        false,
        batch.items().stream().map(r -> byItem.get(r.itemId())).toList());
  }
}
