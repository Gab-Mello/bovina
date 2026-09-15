package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.infrastructure.CryostorageFacts;
import com.bovina.cryostorage.infrastructure.EmbryoPackageRepository;
import com.bovina.cryostorage.infrastructure.InventoryStore;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import com.bovina.protocols.application.Protocols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThawEvents {
  private final TenantAccess access;
  private final EmbryoPackageRepository packages;
  private final CryostorageFacts members;
  private final InventoryStore store;
  private final OpuFacilities facilities;
  private final Protocols protocols;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public ThawEvents(
      TenantAccess access,
      EmbryoPackageRepository packages,
      CryostorageFacts members,
      InventoryStore store,
      OpuFacilities facilities,
      Protocols protocols,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.packages = packages;
    this.members = members;
    this.store = store;
    this.facilities = facilities;
    this.protocols = protocols;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Result thaw(ExecutionContext c, UUID key, InventoryStore.Thaw intent) {
    access.require(c, "inventory:write");
    validate(intent);
    if (!key.equals(intent.id())) throw rejected("THAW_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c, key, "THAW_PACKAGE_ITEMS_V1", intent, Result.class, () -> thawOnce(c, intent));
  }

  private Result thawOnce(ExecutionContext c, InventoryStore.Thaw intent) {
    var p = packages.lock(c.tenantId(), intent.packageId()).orElseThrow(ThawEvents::missing);
    p.requireSealed();
    var ledger = store.movements(c.tenantId(), p.id());
    if (p.currentLocationId() != null
        || ledger.isEmpty()
        || !ledger.getLast().type().equals("WITHDRAW"))
      throw conflict("PACKAGE_MUST_BE_WITHDRAWN_FOR_THAW");
    if (store.hasActiveHold(c.tenantId(), p.id())) throw conflict("PACKAGE_ON_HOLD");
    facilities.requireProfessional(c.tenantId(), intent.professionalId());
    if (intent.protocolVersionId() != null)
      protocols.requireApplicable(
          c,
          intent.protocolVersionId(),
          "THAW",
          LocalDate.ofInstant(intent.occurredAt(), ZoneOffset.UTC));
    var selected = new HashSet<>(intent.packageItemIds());
    var items =
        members.activeMembers(c.tenantId(), p.id()).stream()
            .filter(m -> selected.contains(m.id()))
            .toList();
    if (items.size() != selected.size()) throw rejected("PACKAGE_ITEM_NOT_ACTIVE");
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    store.insertThaw(c, intent, now);
    store.thawMembers(c, intent.id(), p.id(), items, now);
    p.membersThawed(now);
    packages.flush();
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            now,
            "THAW",
            "THAW_EVENT",
            intent.id(),
            null,
            null,
            null,
            "items=" + items.size()));
    return new Result(
        intent.id(), p.id(), items.stream().map(CryostorageFacts.PackageMember::embryoId).toList());
  }

  private static void validate(InventoryStore.Thaw intent) {
    StableIds.requireVersion7(intent.id());
    StableIds.requireVersion7(intent.packageId());
    StableIds.requireVersion7(intent.professionalId());
    if (intent.protocolVersionId() != null) StableIds.requireVersion7(intent.protocolVersionId());
    if (intent.occurredAt() == null
        || intent.resultCode() == null
        || !intent.resultCode().matches("[A-Z][A-Z0-9_]{0,79}")
        || intent.notes() != null && intent.notes().length() > 2000
        || intent.packageItemIds() == null
        || intent.packageItemIds().isEmpty()
        || intent.packageItemIds().size() > 100
        || new HashSet<>(intent.packageItemIds()).size() != intent.packageItemIds().size())
      throw rejected("INVALID_THAW_EVENT");
    intent.packageItemIds().forEach(StableIds::requireVersion7);
  }

  public record Result(UUID id, UUID packageId, List<UUID> embryoIds) {
    public Result {
      embryoIds = List.copyOf(embryoIds);
    }
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Thaw request is invalid");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Package is not eligible for thaw");
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "PACKAGE_NOT_FOUND", "Package not found");
  }
}
