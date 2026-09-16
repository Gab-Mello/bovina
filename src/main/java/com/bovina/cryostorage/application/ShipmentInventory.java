package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.domain.EmbryoPackage;
import com.bovina.cryostorage.infrastructure.CryostorageFacts;
import com.bovina.cryostorage.infrastructure.EmbryoPackageRepository;
import com.bovina.cryostorage.infrastructure.InventoryStore;
import com.bovina.embryology.application.EmbryoCryopreservationBoundary;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shipment orchestration joins the same package locks and physical ledger as ordinary inventory.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ShipmentInventory {
  private final EmbryoPackageRepository packages;
  private final InventoryStore store;
  private final CryostorageFacts members;
  private final EmbryoCryopreservationBoundary embryos;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public ShipmentInventory(
      EmbryoPackageRepository packages,
      InventoryStore store,
      CryostorageFacts members,
      EmbryoCryopreservationBoundary embryos,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.packages = packages;
    this.store = store;
    this.members = members;
    this.embryos = embryos;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  public Map<UUID, Integer> validateReservation(
      ExecutionContext c, UUID establishment, List<Item> items) {
    var locked = lock(c, items.stream().map(Item::packageId).toList());
    var quantities = new HashMap<UUID, Integer>();
    var embryoIds = new ArrayList<UUID>();
    for (var item : items) {
      var p = locked.get(item.packageId());
      requireStock(c, p, item, establishment);
      if (store.hasShipmentReservation(c.tenantId(), p.id()))
        throw conflict("PACKAGE_RESERVED_FOR_SHIPMENT");
      var active = members.activeMembers(c.tenantId(), p.id());
      if (active.isEmpty()) throw conflict("PACKAGE_HAS_NO_ACTIVE_MATERIAL");
      embryoIds.addAll(active.stream().map(CryostorageFacts.PackageMember::embryoId).toList());
      quantities.put(item.id(), active.size());
    }
    embryos.requireShippablePackageMembers(c.tenantId(), embryoIds);
    return Map.copyOf(quantities);
  }

  public void reserve(ExecutionContext c, List<Item> items) {
    var now = now();
    for (var item : items) store.reserveShipment(c, item.id(), item.packageId(), now);
  }

  public void cancel(ExecutionContext c, List<Item> items) {
    lock(c, items.stream().map(Item::packageId).toList());
    for (var item : items) {
      if (!store.ownsShipmentReservation(c.tenantId(), item.id(), item.packageId()))
        throw conflict("SHIPMENT_RESERVATION_CHANGED");
      store.releaseShipment(c.tenantId(), item.id(), "CANCELLED", now());
    }
  }

  public void dispatch(
      ExecutionContext c, UUID establishment, List<Item> items, Instant occurredAt) {
    var locked = lock(c, items.stream().map(Item::packageId).toList());
    var embryoIds = new ArrayList<UUID>();
    for (var item : items) {
      var p = locked.get(item.packageId());
      requireStock(c, p, item, establishment);
      if (!store.ownsShipmentReservation(c.tenantId(), item.id(), p.id()))
        throw conflict("SHIPMENT_RESERVATION_CHANGED");
      var active = members.activeMembers(c.tenantId(), p.id());
      if (active.size() != item.quantity()) throw conflict("SHIPMENT_PACKAGE_MEMBERS_CHANGED");
      embryoIds.addAll(active.stream().map(CryostorageFacts.PackageMember::embryoId).toList());
      var latest = store.latestMovement(c.tenantId(), p.id());
      if (latest == null || occurredAt.isBefore(latest.occurredAt()))
        throw conflict("DISPATCH_PRECEDES_PHYSICAL_RECEIPT");
    }
    embryos.shipPackageMembers(c.tenantId(), embryoIds);
    for (var item : items) {
      var p = locked.get(item.packageId());
      var from = p.currentLocationId();
      p.applyPhysicalMovement(from, null);
      store.appendShipmentMovement(
          c,
          ids.next(),
          item.id(),
          p.id(),
          p.lastMovementSequence(),
          "SHIP",
          from,
          null,
          occurredAt,
          null,
          now());
      store.releaseShipment(c.tenantId(), item.id(), "DISPATCHED", now());
      recordAudit(c, item, "SHIP", from, null);
    }
    packages.flush();
  }

  public void returnPackage(
      ExecutionContext c,
      Item item,
      UUID movementId,
      UUID destination,
      long expectedVersion,
      Instant occurredAt,
      String reason) {
    var p = lock(c, List.of(item.packageId())).get(item.packageId());
    p.requireSealed();
    if (p.version() != expectedVersion) throw conflict("STALE_PACKAGE_VERSION");
    if (p.currentLocationId() != null || !store.isLatestShipment(c.tenantId(), p.id(), item.id()))
      throw conflict("PACKAGE_NOT_AT_SHIPMENT_DESTINATION");
    var latest = store.latestMovement(c.tenantId(), p.id());
    if (occurredAt.isBefore(latest.occurredAt())) throw conflict("RETURN_PRECEDES_DISPATCH");
    if (!store.activeLocation(c.tenantId(), p.establishmentId(), destination))
      throw conflict("STORAGE_LOCATION_NOT_ACTIVE");
    p.applyPhysicalMovement(null, destination);
    packages.flush();
    store.appendShipmentMovement(
        c,
        movementId,
        item.id(),
        p.id(),
        p.lastMovementSequence(),
        "RETURN",
        null,
        destination,
        occurredAt,
        reason,
        now());
    // Custody is restored; availability remains SHIPPED_OUT until a validated policy/correction.
    recordAudit(c, item, "RETURN", null, destination);
  }

  public List<HoldResult> placeRecallHolds(
      ExecutionContext c, List<HoldIntent> intents, String reason) {
    var locked = lock(c, intents.stream().map(HoldIntent::packageId).toList());
    var results = new ArrayList<HoldResult>();
    for (var input : intents) {
      var p = locked.get(input.packageId());
      String result;
      UUID holdId = null;
      if (p.version() != input.expectedVersion()) result = "STALE_PACKAGE_VERSION";
      else if (p.status() != EmbryoPackage.Status.SEALED
          || members.activeMembers(c.tenantId(), p.id()).isEmpty()) result = "MATERIAL_UNAVAILABLE";
      else if (p.currentLocationId() == null) result = "OUT_OF_CUSTODY";
      else if (store.hasActiveHold(c.tenantId(), p.id())) result = "ALREADY_HELD";
      else {
        holdId = input.id();
        store.openHold(c, new InventoryStore.Hold(holdId, p.id(), "RECALL", reason), now());
        audit.record(
            new AuditEvent(
                ids.next(),
                c,
                now(),
                "OPEN",
                "INVENTORY_HOLD",
                holdId,
                null,
                reason,
                null,
                "RECALL"));
        result = "HOLD_PLACED";
      }
      results.add(new HoldResult(p.id(), holdId, result, p.version(), p.currentLocationId()));
    }
    return List.copyOf(results);
  }

  private Map<UUID, EmbryoPackage> lock(ExecutionContext c, List<UUID> ids) {
    var result = new HashMap<UUID, EmbryoPackage>();
    for (var id : new TreeSet<>(ids))
      result.put(
          id,
          packages
              .lock(c.tenantId(), id)
              .orElseThrow(
                  () ->
                      new ApplicationFailure(
                          ApplicationFailure.Kind.NOT_FOUND,
                          "PACKAGE_NOT_FOUND",
                          "Package not found")));
    return result;
  }

  private void requireStock(ExecutionContext c, EmbryoPackage p, Item item, UUID establishment) {
    p.requireSealed();
    if (!p.establishmentId().equals(establishment)) throw conflict("PACKAGE_ORIGIN_MISMATCH");
    if (p.version() != item.expectedVersion()) throw conflict("STALE_PACKAGE_VERSION");
    if (p.currentLocationId() == null || !p.currentLocationId().equals(item.expectedLocationId()))
      throw conflict("PACKAGE_NOT_IN_EXPECTED_LOCATION");
    if (store.hasActiveHold(c.tenantId(), p.id())) throw conflict("PACKAGE_ON_HOLD");
    if (!store.activeLocation(c.tenantId(), establishment, p.currentLocationId()))
      throw conflict("STORAGE_LOCATION_NOT_ACTIVE");
  }

  private void recordAudit(ExecutionContext c, Item item, String action, UUID from, UUID to) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            now(),
            action,
            "SHIPMENT_ITEM",
            item.id(),
            null,
            null,
            from == null ? null : from.toString(),
            to == null ? null : to.toString()));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Package is incompatible with shipment operation");
  }

  public record Item(
      UUID id, UUID packageId, long expectedVersion, UUID expectedLocationId, int quantity) {}

  public record HoldIntent(UUID id, UUID packageId, long expectedVersion) {}

  public record HoldResult(
      UUID packageId, UUID holdId, String outcome, long observedVersion, UUID observedLocation) {}
}
