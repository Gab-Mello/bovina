package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.domain.CryopreservationBatch;
import com.bovina.cryostorage.infrastructure.CryostorageFacts;
import com.bovina.embryology.application.EmbryoCryopreservationBoundary;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import com.bovina.protocols.application.Protocols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Cryopreservations {
  private final TenantAccess access;
  private final OpuFacilities facilities;
  private final Protocols protocols;
  private final EmbryoCryopreservationBoundary embryos;
  private final CryostorageFacts facts;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public Cryopreservations(
      TenantAccess access,
      OpuFacilities facilities,
      Protocols protocols,
      EmbryoCryopreservationBoundary embryos,
      CryostorageFacts facts,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.facilities = facilities;
    this.protocols = protocols;
    this.embryos = embryos;
    this.facts = facts;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Result cryopreserve(ExecutionContext c, UUID key, CryopreservationBatch batch) {
    access.require(c, "inventory:write");
    if (!key.equals(batch.eventId())) throw rejected("EVENT_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c, key, "CRYOPRESERVE_EMBRYOS_V1", batch, Result.class, () -> cryopreserveOnce(c, batch));
  }

  private Result cryopreserveOnce(ExecutionContext c, CryopreservationBatch batch) {
    facilities.requireDestination(c.tenantId(), batch.establishmentId(), null);
    facilities.requireProfessional(c.tenantId(), batch.professionalId());
    if (batch.protocolVersionId() != null)
      protocols.requireApplicable(
          c,
          batch.protocolVersionId(),
          "CRYOPRESERVATION",
          LocalDate.ofInstant(batch.occurredAt(), java.time.ZoneOffset.UTC));
    var requested = batch.items().stream().map(CryopreservationBatch.Item::embryoId).toList();
    var snapshots = embryos.lockForCryopreservation(c.tenantId(), requested);
    for (var item : batch.items()) {
      var snapshot = snapshots.get(item.embryoId());
      if (snapshot.embryoVersion() != item.expectedEmbryoVersion())
        throw conflict("STALE_EMBRYO_VERSION");
      if (batch.occurredAt().isBefore(snapshot.identifiedAt())
          || batch.occurredAt().isBefore(snapshot.evaluatedAt()))
        throw rejected("CRYOPRESERVATION_PRECEDES_SOURCE_FACTS");
    }
    if (!facts.previouslyCryopreserved(c.tenantId(), requested).isEmpty())
      throw conflict("EMBRYO_ALREADY_CRYOPRESERVED");
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    facts.insertCryopreservation(c, batch, snapshots, now);
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            now,
            "CRYOPRESERVE",
            "CRYOPRESERVATION_EVENT",
            batch.eventId(),
            null,
            null,
            null,
            "items=" + batch.items().size()));
    return new Result(batch.eventId(), batch.items().stream().map(i -> i.id()).toList());
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Invalid cryopreservation request");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Embryo cannot be cryopreserved");
  }

  public record Result(UUID eventId, List<UUID> itemIds) {
    public Result {
      itemIds = List.copyOf(itemIds);
    }
  }
}
