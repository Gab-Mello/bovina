package com.bovina.opu.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.opu.domain.ExternalOocyteReceipt;
import com.bovina.opu.infrastructure.IntakeStore;
import com.bovina.parties.application.*;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiveExternalOocytes {
  private final IntakeCapabilities features;
  private final TenantAccess access;
  private final OpuFacilities facilities;
  private final FarmProperties farms;
  private final CounterpartyAccess owners;
  private final DocumentReferences documents;
  private final IntakeStore store;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public ReceiveExternalOocytes(
      IntakeCapabilities features,
      TenantAccess access,
      OpuFacilities facilities,
      FarmProperties farms,
      CounterpartyAccess owners,
      DocumentReferences documents,
      IntakeStore store,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.features = features;
    this.access = access;
    this.facilities = facilities;
    this.farms = farms;
    this.owners = owners;
    this.documents = documents;
    this.store = store;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public ExternalReceiptView recordExternal(
      ExecutionContext c, UUID key, ExternalOocyteReceipt input) {
    access.require(c, "opu:write");
    requireEnabled();
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_EXTERNAL_OOCYTES_V1",
        input,
        ExternalReceiptView.class,
        () -> {
          var snapshot =
              input.sourceFarmPropertyId() == null
                  ? null
                  : farms.origin(c, input.sourceFarmPropertyId(), false);
          if (input.ownerId() != null) owners.requireActive(c.tenantId(), input.ownerId());
          if (input.collectingProfessionalId() != null)
            facilities.requireProfessional(c.tenantId(), input.collectingProfessionalId());
          if (input.sourceDocumentId() != null)
            documents.requireReference(c.tenantId(), input.sourceDocumentId());
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.external(c, input, snapshot, now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "RECORD",
                  "EXTERNAL_OOCYTE_RECEIPT",
                  input.id(),
                  null,
                  null,
                  null,
                  "RECEIVED"));
          return new ExternalReceiptView(
              input,
              "RECEIVED",
              snapshot,
              IntakeStore.provenance(input.sourceDocumentId(), c.actorId(), now));
        });
  }

  @Transactional(readOnly = true)
  public ExternalReceiptView external(ExecutionContext c, UUID id) {
    access.require(c, "opu:read");
    requireEnabled();
    return store
        .external(c.tenantId(), id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND,
                    "RECEIPT_NOT_FOUND",
                    "External receipt not found"));
  }

  private void requireEnabled() {
    if (!features.externalReceiptEnabled())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND,
          "INTAKE_NOT_ENABLED",
          "External receipt is not enabled");
  }
}
