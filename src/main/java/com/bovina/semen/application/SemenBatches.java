package com.bovina.semen.application;

import com.bovina.animals.application.SireDirectory;
import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.CounterpartyAccess;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.semen.domain.*;
import com.bovina.semen.infrastructure.SemenStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SemenBatches {
  private final TenantAccess access;
  private final SemenStore store;
  private final SireDirectory sires;
  private final CounterpartyAccess parties;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public SemenBatches(
      TenantAccess access,
      SemenStore store,
      SireDirectory sires,
      CounterpartyAccess parties,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.store = store;
    this.sires = sires;
    this.parties = parties;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public ExternalEstablishmentReference registerProducer(
      ExecutionContext c, UUID key, ExternalEstablishmentReference input) {
    access.require(c, "semen:write");
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_EXTERNAL_ESTABLISHMENT_V1",
        input,
        ExternalEstablishmentReference.class,
        () -> {
          if (input.legalPartyId() != null)
            parties.requireActive(c.tenantId(), input.legalPartyId());
          if (input.verificationDocumentId() != null)
            documents.requireReference(c.tenantId(), input.verificationDocumentId());
          store.insertExternal(c.tenantId(), input, c.actorId(), now());
          record(c, "EXTERNAL_ESTABLISHMENT", input.id());
          return input;
        });
  }

  @Transactional
  public SemenBatch registerBatch(ExecutionContext c, UUID key, Registration input) {
    access.require(c, "semen:write");
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_SEMEN_BATCH_V1",
        input,
        SemenBatch.class,
        () -> {
          var producer = producer(c.tenantId(), input.producerEstablishmentId(), true);
          producer.requireActive();
          sires.snapshot(c.tenantId(), input.sireId());
          if (input.ownerId() != null) parties.requireActive(c.tenantId(), input.ownerId());
          if (input.sourceDocumentId() != null)
            documents.requireReference(c.tenantId(), input.sourceDocumentId());
          var time = now();
          var provenance =
              new DataProvenance(
                  DataProvenance.Origin.MANUAL,
                  input.sourceDocumentId(),
                  null,
                  null,
                  c.actorId(),
                  time,
                  null,
                  null,
                  null);
          var batch =
              new SemenBatch(
                  input.id(),
                  input.batchCode(),
                  input.sireId(),
                  input.producerEstablishmentId(),
                  input.provenanceCode(),
                  input.verificationStatus(),
                  input.semenType(),
                  input.ownerId(),
                  input.receivedAt(),
                  "ACTIVE",
                  0,
                  provenance);
          store.insertBatch(c.tenantId(), batch);
          record(c, "SEMEN_BATCH", batch.id());
          return batch;
        });
  }

  @Transactional(readOnly = true)
  public SemenBatch get(ExecutionContext c, UUID id) {
    access.require(c, "semen:read");
    return store.batch(c.tenantId(), id, false).orElseThrow(() -> missing("SEMEN_BATCH"));
  }

  @Transactional(readOnly = true)
  public PageResult<SemenBatch> batches(ExecutionContext c, SearchPage page) {
    access.require(c, "semen:read");
    return new PageResult<>(
        store.batchPage(c.tenantId(), page.size(), page.offset()), page.page(), page.size());
  }

  @Transactional(readOnly = true)
  public ExternalEstablishmentReference getProducer(ExecutionContext c, UUID id) {
    access.require(c, "semen:read");
    return producer(c.tenantId(), id, false);
  }

  @Transactional(readOnly = true)
  public PageResult<ExternalEstablishmentReference> producers(ExecutionContext c, SearchPage page) {
    access.require(c, "semen:read");
    return new PageResult<>(
        store.externalPage(c.tenantId(), page.size(), page.offset()), page.page(), page.size());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public SemenLineage lockLineage(UUID tenant, UUID id) {
    var batch = store.batch(tenant, id, true).orElseThrow(() -> missing("SEMEN_BATCH"));
    batch.requireActive();
    var producer = producer(tenant, batch.producerEstablishmentId(), true);
    producer.requireActive();
    return new SemenLineage(batch, sires.snapshot(tenant, batch.sireId()), producer);
  }

  private ExternalEstablishmentReference producer(UUID tenant, UUID id, boolean lock) {
    return store.external(tenant, id, lock).orElseThrow(() -> missing("EXTERNAL_ESTABLISHMENT"));
  }

  private void record(ExecutionContext c, String type, UUID id) {
    audit.record(
        new AuditEvent(ids.next(), c, now(), "REGISTER", type, id, 0L, null, null, "ACTIVE"));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApplicationFailure missing(String type) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND,
        type + "_NOT_FOUND",
        "Semen provenance record not found");
  }

  public record Registration(
      UUID id,
      String batchCode,
      UUID sireId,
      UUID producerEstablishmentId,
      String provenanceCode,
      String verificationStatus,
      String semenType,
      UUID ownerId,
      Instant receivedAt,
      UUID sourceDocumentId) {}
}
