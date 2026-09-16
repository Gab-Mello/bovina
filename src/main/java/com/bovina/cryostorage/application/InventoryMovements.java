package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.infrastructure.CryostorageFacts;
import com.bovina.cryostorage.infrastructure.EmbryoPackageRepository;
import com.bovina.cryostorage.infrastructure.InventoryStore;
import com.bovina.embryology.application.EmbryoCryopreservationBoundary;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.StableIds;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryMovements {
  private final TenantAccess access;
  private final EmbryoPackageRepository packages;
  private final InventoryStore store;
  private final CryostorageFacts members;
  private final EmbryoCryopreservationBoundary embryos;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public InventoryMovements(
      TenantAccess access,
      EmbryoPackageRepository packages,
      InventoryStore store,
      CryostorageFacts members,
      EmbryoCryopreservationBoundary embryos,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.packages = packages;
    this.store = store;
    this.members = members;
    this.embryos = embryos;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Result record(ExecutionContext c, UUID key, Intent intent) {
    access.require(c, intent.type() == Type.ADJUST ? "inventory:adjust" : "inventory:write");
    if (intent.type() == Type.SHIP || intent.type() == Type.RETURN)
      throw rejected("DISTRIBUTION_MOVEMENT_REQUIRES_SHIPMENT_WORKFLOW");
    if (!key.equals(intent.id())) throw rejected("MOVEMENT_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c, key, "RECORD_PHYSICAL_MOVEMENT_V1", intent, Result.class, () -> append(c, intent, null));
  }

  @Transactional
  public Batch.Result bulkRecord(ExecutionContext c, UUID key, Batch batch) {
    access.require(c, "inventory:write");
    if (!key.equals(batch.batchId())) throw rejected("MOVEMENT_BATCH_KEY_MISMATCH");
    if (batch.items().stream()
        .anyMatch(i -> i.type() == Type.ADJUST || i.type() == Type.SHIP || i.type() == Type.RETURN))
      throw rejected("BULK_MOVEMENT_TYPE_REQUIRES_DEDICATED_WORKFLOW");
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_PHYSICAL_MOVEMENTS_BULK_V1",
        batch,
        Batch.Result.class,
        () -> {
          for (var packageId :
              new TreeSet<>(batch.items().stream().map(Intent::packageId).toList()))
            packages.lock(c.tenantId(), packageId).orElseThrow(InventoryMovements::missing);
          var results = new java.util.ArrayList<Result>(batch.items().size());
          for (var item : batch.items()) results.add(append(c, item, null));
          return new Batch.Result(batch.batchId(), results);
        });
  }

  // A reconciliation resolution is one ordinary, audited physical movement, not an edit to history.
  @Transactional
  public Result resolve(ExecutionContext c, UUID key, Intent intent, UUID reconciliationId) {
    access.require(c, "inventory:adjust");
    if (intent.type() != Type.ADJUST || !key.equals(intent.id()))
      throw rejected("INVALID_RECONCILIATION_ADJUSTMENT");
    return receipts.replayOrExecute(
        c,
        key,
        "RESOLVE_RECONCILIATION_V1",
        new Resolution(intent, reconciliationId),
        Result.class,
        () -> append(c, intent, reconciliationId));
  }

  private Result append(ExecutionContext c, Intent intent, UUID reconciliationId) {
    var p =
        packages.lock(c.tenantId(), intent.packageId()).orElseThrow(InventoryMovements::missing);
    if (p.version() != intent.expectedVersion()) throw conflict("STALE_PACKAGE_VERSION");
    p.requireSealed();
    if (store.hasShipmentReservation(c.tenantId(), p.id()))
      throw conflict("PACKAGE_RESERVED_FOR_SHIPMENT");
    var latest = store.latestMovement(c.tenantId(), p.id());
    if (p.currentLocationId() == null && latest != null && latest.type().equals("SHIP"))
      throw conflict("SHIPPED_PACKAGE_REQUIRES_RETURN_WORKFLOW");
    if (!Objects.equals(p.currentLocationId(), intent.expectedLocationId()))
      throw conflict("PACKAGE_NOT_IN_EXPECTED_LOCATION");
    if (store.hasActiveHold(c.tenantId(), p.id())) throw conflict("PACKAGE_ON_HOLD");
    var active = members.activeMembers(c.tenantId(), p.id());
    if (active.isEmpty()) throw conflict("PACKAGE_HAS_NO_ACTIVE_MATERIAL");
    validate(intent, p.currentLocationId());
    if (intent.destinationId() != null
        && !store.activeLocation(c.tenantId(), p.establishmentId(), intent.destinationId()))
      throw rejected("STORAGE_LOCATION_NOT_ACTIVE");
    var from = p.currentLocationId();
    if (intent.type() == Type.DISPOSE)
      embryos.discardPackageMembers(
          c.tenantId(), active.stream().map(CryostorageFacts.PackageMember::embryoId).toList());
    p.applyPhysicalMovement(from, intent.destinationId());
    if (intent.type() == Type.DISPOSE) p.dispose();
    packages.flush();
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    store.appendMovement(
        c,
        new InventoryStore.Movement(
            intent.id(),
            intent.packageId(),
            intent.type().name(),
            intent.expectedLocationId(),
            intent.destinationId(),
            intent.expectedVersion(),
            intent.occurredAt(),
            intent.reason()),
        p.lastMovementSequence(),
        from,
        intent.destinationId(),
        reconciliationId,
        now);
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            now,
            intent.type().name(),
            "INVENTORY_MOVEMENT",
            intent.id(),
            p.version(),
            intent.reason(),
            from == null ? null : from.toString(),
            intent.destinationId() == null ? null : intent.destinationId().toString()));
    return new Result(
        intent.id(), p.id(), p.lastMovementSequence(), p.currentLocationId(), p.version());
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryStore.MovementRow> ledger(
      ExecutionContext c, UUID packageId, com.bovina.platform.application.SearchPage page) {
    access.require(c, "inventory:read");
    if (packages.findByOrganizationIdAndId(c.tenantId(), packageId).isEmpty()) throw missing();
    return new PageResult<>(
        store.movementsPage(c.tenantId(), packageId, page.page(), page.size()),
        page.page(),
        page.size());
  }

  @Transactional(readOnly = true)
  public boolean projectionMatchesReplay(ExecutionContext c, UUID packageId) {
    access.require(c, "inventory:read");
    var p =
        packages
            .findByOrganizationIdAndId(c.tenantId(), packageId)
            .orElseThrow(InventoryMovements::missing);
    UUID location = null;
    long sequence = 0;
    for (var movement : store.movements(c.tenantId(), packageId)) {
      if (movement.sequence() != ++sequence || !Objects.equals(movement.fromLocationId(), location))
        return false;
      location = movement.toLocationId();
    }
    return sequence == p.lastMovementSequence() && Objects.equals(location, p.currentLocationId());
  }

  private static void validate(Intent intent, UUID current) {
    var destination = intent.destinationId();
    if (Objects.equals(current, destination)) throw conflict("PACKAGE_LOCATION_UNCHANGED");
    switch (intent.type()) {
      case RECEIVE, STORE, RETURN -> {
        if (current != null || destination == null) throw rejected("INVALID_RECEIPT_LOCATION");
      }
      case MOVE -> {
        if (current == null || destination == null) throw rejected("INVALID_MOVE_LOCATION");
      }
      case WITHDRAW, SHIP, DISPOSE -> {
        if (current == null || destination != null) throw rejected("INVALID_WITHDRAWAL_LOCATION");
      }
      case ADJUST -> {
        if (intent.reason() == null || intent.reason().isBlank())
          throw rejected("ADJUSTMENT_REQUIRES_REASON");
      }
    }
    if (intent.type() == Type.DISPOSE && (intent.reason() == null || intent.reason().isBlank()))
      throw rejected("DISPOSAL_REQUIRES_REASON");
  }

  public record Intent(
      UUID id,
      UUID packageId,
      Type type,
      UUID expectedLocationId,
      UUID destinationId,
      long expectedVersion,
      Instant occurredAt,
      String reason) {
    public Intent {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(packageId);
      if (type == null
          || occurredAt == null
          || expectedVersion < 0
          || (reason != null && reason.length() > 500)) throw rejected("INVALID_MOVEMENT");
    }
  }

  public record Result(
      UUID id, UUID packageId, long sequence, UUID currentLocationId, long version) {}

  public record Batch(UUID batchId, List<Intent> items) {
    public Batch {
      StableIds.requireVersion7(batchId);
      if (items == null || items.isEmpty() || items.size() > 100)
        throw rejected("INVALID_MOVEMENT_BATCH");
      items = List.copyOf(items);
      if (new HashSet<>(items.stream().map(Intent::id).toList()).size() != items.size())
        throw rejected("DUPLICATE_MOVEMENT_ID");
    }

    public record Result(UUID batchId, List<InventoryMovements.Result> items) {
      public Result {
        items = List.copyOf(items);
      }
    }
  }

  private record Resolution(Intent intent, UUID reconciliationId) {}

  public enum Type {
    RECEIVE,
    STORE,
    MOVE,
    WITHDRAW,
    SHIP,
    RETURN,
    DISPOSE,
    ADJUST
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Invalid inventory movement");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Inventory state has changed");
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "PACKAGE_NOT_FOUND", "Package not found");
  }
}
