package com.bovina.opu.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.opu.domain.TransportReceipt;
import com.bovina.opu.infrastructure.*;
import com.bovina.platform.application.*;
import com.bovina.protocols.application.Protocols;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OocyteTransports {
  private final IntakeCapabilities features;
  private final TenantAccess access;
  private final OpuFacilities facilities;
  private final Protocols protocols;
  private final DocumentReferences documents;
  private final OpuSessionRepository sessions;
  private final IntakeStore store;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public OocyteTransports(
      IntakeCapabilities features,
      TenantAccess access,
      OpuFacilities facilities,
      Protocols protocols,
      DocumentReferences documents,
      OpuSessionRepository sessions,
      IntakeStore store,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.features = features;
    this.access = access;
    this.facilities = facilities;
    this.protocols = protocols;
    this.documents = documents;
    this.sessions = sessions;
    this.store = store;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public TransportView recordTransport(ExecutionContext c, UUID key, TransportReceipt input) {
    access.require(c, "opu:write");
    requireEnabled();
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_OOCYTE_TRANSPORT_V1",
        input,
        TransportView.class,
        () -> {
          sessions
              .findByOrganizationIdAndId(c.tenantId(), input.sourceSessionId())
              .orElseThrow(OpuSessions::missing);
          facilities.requireDestination(
              c.tenantId(),
              input.destinationEstablishmentId(),
              input.destinationOperationalLocationId());
          if (input.protocolVersionId() != null)
            protocols.requireApplicable(
                c, input.protocolVersionId(), "OOCYTE_TRANSPORT", input.protocolAppliedOn());
          if (input.sourceDocumentId() != null)
            documents.requireReference(c.tenantId(), input.sourceDocumentId());
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.transport(c, input, now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "RECORD",
                  "OOCYTE_TRANSPORT",
                  input.id(),
                  null,
                  null,
                  null,
                  "RECEIVED"));
          return new TransportView(
              input, IntakeStore.provenance(input.sourceDocumentId(), c.actorId(), now));
        });
  }

  @Transactional(readOnly = true)
  public TransportView transport(ExecutionContext c, UUID id) {
    access.require(c, "opu:read");
    requireEnabled();
    return store
        .transport(c.tenantId(), id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND,
                    "TRANSPORT_NOT_FOUND",
                    "Transport observation not found"));
  }

  private void requireEnabled() {
    if (!features.transportEnabled())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND,
          "INTAKE_NOT_ENABLED",
          "Transport recording is not enabled");
  }
}
