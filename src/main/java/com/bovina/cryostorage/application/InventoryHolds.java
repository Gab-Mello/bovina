package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.infrastructure.EmbryoPackageRepository;
import com.bovina.cryostorage.infrastructure.InventoryStore;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryHolds {
  private final TenantAccess access;
  private final EmbryoPackageRepository packages;
  private final InventoryStore store;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public InventoryHolds(
      TenantAccess access,
      EmbryoPackageRepository packages,
      InventoryStore store,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.packages = packages;
    this.store = store;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public View open(ExecutionContext c, UUID key, InventoryStore.Hold hold) {
    access.require(c, "inventory:write");
    validate(hold);
    if (!key.equals(hold.id())) throw rejected("HOLD_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "OPEN_INVENTORY_HOLD_V1",
        hold,
        View.class,
        () -> {
          var p =
              packages
                  .lock(c.tenantId(), hold.packageId())
                  .orElseThrow(InventoryHolds::missingPackage);
          p.requireSealed();
          var latest = store.latestMovement(c.tenantId(), p.id());
          if (p.currentLocationId() == null && latest != null && latest.type().equals("SHIP"))
            throw conflict("PACKAGE_OUT_OF_CUSTODY");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.openHold(c, hold, now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "OPEN",
                  "INVENTORY_HOLD",
                  hold.id(),
                  null,
                  hold.reason(),
                  null,
                  hold.typeCode()));
          return new View(hold.id(), hold.packageId(), hold.typeCode(), hold.reason(), now, null);
        });
  }

  @Transactional
  public View release(ExecutionContext c, UUID key, UUID holdId, String reason) {
    access.require(c, "inventory:write");
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw rejected("INVALID_HOLD_RELEASE_REASON");
    return receipts.replayOrExecute(
        c,
        key,
        "RELEASE_INVENTORY_HOLD_V1",
        new Release(holdId, reason),
        View.class,
        () -> {
          var h = store.hold(c.tenantId(), holdId);
          if (h == null) throw missingHold();
          packages.lock(c.tenantId(), h.packageId()).orElseThrow(InventoryHolds::missingPackage);
          h = store.hold(c.tenantId(), holdId);
          if (h.releasedAt() != null) throw conflict("HOLD_ALREADY_RELEASED");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.releaseHold(c, holdId, reason, now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "RELEASE",
                  "INVENTORY_HOLD",
                  holdId,
                  null,
                  reason,
                  h.typeCode(),
                  "RELEASED"));
          return new View(holdId, h.packageId(), h.typeCode(), h.reason(), h.openedAt(), now);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID holdId) {
    access.require(c, "inventory:read");
    var h = store.hold(c.tenantId(), holdId);
    if (h == null) throw missingHold();
    return new View(h.id(), h.packageId(), h.typeCode(), h.reason(), h.openedAt(), h.releasedAt());
  }

  private static void validate(InventoryStore.Hold hold) {
    StableIds.requireVersion7(hold.id());
    StableIds.requireVersion7(hold.packageId());
    if (hold.typeCode() == null
        || !hold.typeCode().matches("[A-Z][A-Z0-9_]{0,79}")
        || hold.reason() == null
        || hold.reason().isBlank()
        || hold.reason().length() > 500) throw rejected("INVALID_INVENTORY_HOLD");
  }

  public record View(
      UUID id,
      UUID packageId,
      String typeCode,
      String reason,
      Instant openedAt,
      Instant releasedAt) {}

  private record Release(UUID id, String reason) {}

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, "Invalid inventory hold");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Inventory hold has changed");
  }

  private static ApplicationFailure missingPackage() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "PACKAGE_NOT_FOUND", "Package not found");
  }

  private static ApplicationFailure missingHold() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found");
  }
}
