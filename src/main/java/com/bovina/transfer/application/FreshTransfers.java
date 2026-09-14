package com.bovina.transfer.application;

import com.bovina.audit.application.*;
import com.bovina.embryology.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.transfer.domain.*;
import com.bovina.transfer.infrastructure.TransferStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FreshTransfers {
  private final TenantAccess access;
  private final RecipientCycles cycles;
  private final EmbryoTransferBoundary embryos;
  private final Embryos embryoQueries;
  private final OpuFacilities facilities;
  private final TransferStore store;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public FreshTransfers(
      TenantAccess access,
      RecipientCycles cycles,
      EmbryoTransferBoundary embryos,
      Embryos embryoQueries,
      OpuFacilities facilities,
      TransferStore store,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.cycles = cycles;
    this.embryos = embryos;
    this.embryoQueries = embryoQueries;
    this.facilities = facilities;
    this.store = store;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public TransferBatches.Result reserve(
      ExecutionContext c, UUID key, TransferBatches.Reservation batch) {
    access.require(c, "transfer:write");
    requireBatchKey(key, batch.batchId());
    return receipts.replayOrExecute(
        c,
        key,
        "RESERVE_FRESH_TRANSFERS_V1",
        batch,
        TransferBatches.Result.class,
        () -> reserveOnce(c, batch));
  }

  private TransferBatches.Result reserveOnce(
      ExecutionContext c, TransferBatches.Reservation batch) {
    cycles.lockOpen(
        c.tenantId(),
        batch.items().stream().map(TransferBatches.ReservationItem::recipientCycleId).toList());
    var states =
        embryos.reserveFresh(
            c.tenantId(),
            batch.items().stream()
                .map(
                    i ->
                        new EmbryoTransferBoundary.Expected(
                            i.embryoId(), i.expectedEmbryoVersion()))
                .toList());
    var now = now();
    var reservations =
        batch.items().stream()
            .map(
                i ->
                    new TransferReservation(
                        c.tenantId(),
                        i.reservationId(),
                        i.embryoId(),
                        i.recipientCycleId(),
                        c.actorId(),
                        now))
            .toList();
    store.persistReservations(reservations);
    audit.recordAll(
        reservations.stream()
            .map(
                r ->
                    new AuditEvent(
                        ids.next(),
                        c,
                        now,
                        "RESERVE",
                        "EMBRYO_TRANSFER_RESERVATION",
                        r.id(),
                        0L,
                        null,
                        null,
                        "ACTIVE"))
            .toList());
    return new TransferBatches.Result(
        batch.batchId(),
        batch.items().stream()
            .map(
                i ->
                    new TransferBatches.ItemResult(
                        i.itemId(),
                        i.reservationId(),
                        i.embryoId(),
                        "APPLIED",
                        states.get(i.embryoId()).version()))
            .toList());
  }

  @Transactional
  public TransferBatches.Result perform(
      ExecutionContext c, UUID key, TransferBatches.Performance batch) {
    access.require(c, "transfer:write");
    requireBatchKey(key, batch.batchId());
    return receipts.replayOrExecute(
        c,
        key,
        "PERFORM_FRESH_TRANSFERS_V1",
        batch,
        TransferBatches.Result.class,
        () -> performOnce(c, batch));
  }

  private TransferBatches.Result performOnce(
      ExecutionContext c, TransferBatches.Performance batch) {
    var reservationIds =
        batch.items().stream().map(TransferBatches.PerformanceItem::reservationId).toList();
    var refs = store.reservationRefs(c.tenantId(), reservationIds);
    if (refs.size() != reservationIds.size()) throw missing("TRANSFER_RESERVATION");
    var cycleById =
        cycles.lockOpen(
            c.tenantId(),
            refs.values().stream().map(TransferStore.ReservationRef::recipientCycleId).toList());
    var expected = new ArrayList<EmbryoTransferBoundary.Expected>(batch.items().size());
    for (var item : batch.items()) {
      var ref = refs.get(item.reservationId());
      expected.add(
          new EmbryoTransferBoundary.Expected(ref.embryoId(), item.expectedEmbryoVersion()));
    }
    var states = embryos.performFresh(c.tenantId(), expected);
    var reservations = store.lockReservations(c.tenantId(), new TreeSet<>(reservationIds));
    if (reservations.size() != reservationIds.size()) throw missing("TRANSFER_RESERVATION");
    var now = now();
    var transfers = new ArrayList<EmbryoTransfer>(batch.items().size());
    for (var item : batch.items()) {
      var reservation = reservations.get(item.reservationId());
      var ref = refs.get(item.reservationId());
      if (!reservation.embryoId().equals(ref.embryoId())
          || !reservation.recipientCycleId().equals(ref.recipientCycleId()))
        throw conflict("TRANSFER_RESERVATION_CHANGED", "Transfer reservation has changed");
      reservation.requireActive();
      var state = states.get(reservation.embryoId());
      var cycle = cycleById.get(reservation.recipientCycleId());
      var performedOn = item.performedAt().atZone(ZoneId.of(item.timezone())).toLocalDate();
      if (item.performedAt().isBefore(state.identifiedAt())
          || performedOn.isBefore(cycle.openedOn()))
        throw rejected(
            "TRANSFER_PRECEDES_SOURCE_FACTS",
            "Transfer cannot precede embryo identification or recipient cycle opening");
      facilities.requireProfessional(c.tenantId(), item.operatorProfessionalId());
      reservation.consume(c.actorId(), now);
      transfers.add(
          new EmbryoTransfer(
              item.transferId(),
              reservation.id(),
              reservation.embryoId(),
              reservation.recipientCycleId(),
              item.performedAt().truncatedTo(ChronoUnit.MICROS),
              item.timezone(),
              EmbryoTransfer.Origin.FRESH,
              item.operatorProfessionalId(),
              item.notes(),
              DataProvenance.manual(c.actorId(), now)));
    }
    store.flush();
    store.insertTransfers(c.tenantId(), transfers);
    audit.recordAll(
        transfers.stream()
            .map(
                t ->
                    new AuditEvent(
                        ids.next(),
                        c,
                        now,
                        "PERFORM",
                        "EMBRYO_TRANSFER",
                        t.id(),
                        null,
                        null,
                        null,
                        "PERFORMED"))
            .toList());
    return new TransferBatches.Result(
        batch.batchId(),
        batch.items().stream()
            .map(
                i -> {
                  var embryo = refs.get(i.reservationId()).embryoId();
                  return new TransferBatches.ItemResult(
                      i.itemId(), i.transferId(), embryo, "APPLIED", states.get(embryo).version());
                })
            .toList());
  }

  @Transactional
  public ReservationView cancel(ExecutionContext c, UUID key, UUID reservationId, Cancel input) {
    access.require(c, "transfer:write");
    return receipts.replayOrExecute(
        c,
        key,
        "CANCEL_TRANSFER_RESERVATION_V1",
        new CancelIntent(reservationId, input),
        ReservationView.class,
        () -> {
          var ref =
              Optional.ofNullable(
                      store
                          .reservationRefs(c.tenantId(), List.of(reservationId))
                          .get(reservationId))
                  .orElseThrow(() -> missing("TRANSFER_RESERVATION"));
          var state =
              embryos
                  .releaseFresh(
                      c.tenantId(),
                      List.of(
                          new EmbryoTransferBoundary.Expected(
                              ref.embryoId(), input.expectedEmbryoVersion())))
                  .get(ref.embryoId());
          var reservation =
              Optional.ofNullable(
                      store
                          .lockReservations(c.tenantId(), List.of(reservationId))
                          .get(reservationId))
                  .orElseThrow(() -> missing("TRANSFER_RESERVATION"));
          reservation.cancel(c.actorId(), now(), input.reason());
          store.flush();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now(),
                  "CANCEL",
                  "EMBRYO_TRANSFER_RESERVATION",
                  reservationId,
                  reservation.version(),
                  input.reason(),
                  "ACTIVE",
                  "CANCELLED"));
          return reservationView(reservation, state.version());
        });
  }

  @Transactional(readOnly = true)
  public Details get(ExecutionContext c, UUID id) {
    access.require(c, "transfer:read");
    var transfer = store.transfer(c.tenantId(), id).orElseThrow(() -> missing("EMBRYO_TRANSFER"));
    return new Details(transfer, embryoQueries.lineage(c.tenantId(), transfer.embryoId()));
  }

  @Transactional(readOnly = true)
  public PageResult<EmbryoTransfer> search(
      ExecutionContext c, UUID cycle, UUID embryo, SearchPage page) {
    access.require(c, "transfer:read");
    return new PageResult<>(
        store.transfers(c.tenantId(), cycle, embryo, page.size(), page.offset()),
        page.page(),
        page.size());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireBatchKey(UUID key, UUID batch) {
    if (!batch.equals(key))
      throw rejected("BATCH_KEY_MISMATCH", "Idempotency key must equal batch ID");
  }

  private static ReservationView reservationView(
      TransferReservation reservation, long embryoVersion) {
    return new ReservationView(
        reservation.id(),
        reservation.embryoId(),
        reservation.recipientCycleId(),
        reservation.status().name(),
        reservation.reservedBy(),
        reservation.reservedAt(),
        reservation.endedBy(),
        reservation.endedAt(),
        reservation.endReason(),
        reservation.version(),
        embryoVersion);
  }

  private static ApplicationFailure missing(String subject) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, subject + "_NOT_FOUND", "Transfer record not found");
  }

  private static ApplicationFailure rejected(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, message);
  }

  private static ApplicationFailure conflict(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, code, message);
  }

  public record Cancel(long expectedEmbryoVersion, String reason) {}

  public record ReservationView(
      UUID id,
      UUID embryoId,
      UUID recipientCycleId,
      String status,
      UUID reservedBy,
      Instant reservedAt,
      UUID endedBy,
      Instant endedAt,
      String endReason,
      long version,
      long embryoVersion) {}

  public record Details(EmbryoTransfer transfer, Embryos.Lineage lineage) {}

  private record CancelIntent(UUID reservationId, Cancel input) {}
}
