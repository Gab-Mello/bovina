package com.bovina.transfer.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.embryology.application.EmbryoTransferBoundary;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.transfer.domain.EmbryoTransfer;
import com.bovina.transfer.domain.TransferReservation;
import com.bovina.transfer.infrastructure.TransferStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThawedTransfers {
  private final TenantAccess access;
  private final RecipientCycles cycles;
  private final EmbryoTransferBoundary embryos;
  private final OpuFacilities facilities;
  private final TransferStore transfers;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public ThawedTransfers(
      TenantAccess access,
      RecipientCycles cycles,
      EmbryoTransferBoundary embryos,
      OpuFacilities facilities,
      TransferStore transfers,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.cycles = cycles;
    this.embryos = embryos;
    this.facilities = facilities;
    this.transfers = transfers;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public EmbryoTransfer perform(ExecutionContext c, UUID key, Intent intent) {
    access.require(c, "transfer:write");
    if (!key.equals(intent.transferId())) throw rejected("TRANSFER_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "PERFORM_THAWED_TRANSFER_V1",
        intent,
        EmbryoTransfer.class,
        () -> performOnce(c, intent));
  }

  private EmbryoTransfer performOnce(ExecutionContext c, Intent intent) {
    var cycle =
        cycles
            .lockOpen(c.tenantId(), List.of(intent.recipientCycleId()))
            .get(intent.recipientCycleId());
    var state =
        embryos.performThawed(
            c.tenantId(),
            new EmbryoTransferBoundary.Expected(intent.embryoId(), intent.expectedEmbryoVersion()),
            intent.thawEventId(),
            intent.performedAt());
    var performedOn = intent.performedAt().atZone(ZoneId.of(intent.timezone())).toLocalDate();
    if (intent.performedAt().isBefore(state.identifiedAt())
        || performedOn.isBefore(cycle.openedOn())) throw rejected("TRANSFER_PRECEDES_SOURCE_FACTS");
    facilities.requireProfessional(c.tenantId(), intent.operatorProfessionalId());
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var reservation =
        new TransferReservation(
            c.tenantId(),
            intent.reservationId(),
            intent.embryoId(),
            intent.recipientCycleId(),
            c.actorId(),
            now);
    reservation.consume(c.actorId(), now);
    transfers.persistReservations(List.of(reservation));
    transfers.flush();
    var transfer =
        new EmbryoTransfer(
            intent.transferId(),
            intent.reservationId(),
            intent.embryoId(),
            intent.recipientCycleId(),
            intent.performedAt(),
            intent.timezone(),
            EmbryoTransfer.Origin.THAWED,
            intent.thawEventId(),
            intent.operatorProfessionalId(),
            intent.notes(),
            DataProvenance.manual(c.actorId(), now));
    transfers.insertTransfers(c.tenantId(), List.of(transfer));
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            now,
            "PERFORM",
            "EMBRYO_TRANSFER",
            transfer.id(),
            null,
            null,
            null,
            "THAWED"));
    return transfer;
  }

  public record Intent(
      UUID transferId,
      UUID reservationId,
      UUID embryoId,
      UUID recipientCycleId,
      UUID thawEventId,
      long expectedEmbryoVersion,
      Instant performedAt,
      String timezone,
      UUID operatorProfessionalId,
      String notes) {
    public Intent {
      StableIds.requireVersion7(transferId);
      StableIds.requireVersion7(reservationId);
      StableIds.requireVersion7(embryoId);
      StableIds.requireVersion7(recipientCycleId);
      StableIds.requireVersion7(thawEventId);
      StableIds.requireVersion7(operatorProfessionalId);
      if (performedAt == null || expectedEmbryoVersion < 0)
        throw rejected("INVALID_THAWED_TRANSFER");
    }
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Thawed transfer intent is invalid");
  }
}
