package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.domain.EmbryoPackage;
import com.bovina.cryostorage.infrastructure.CryostorageFacts;
import com.bovina.cryostorage.infrastructure.CryostorageFacts.PackageMember;
import com.bovina.cryostorage.infrastructure.EmbryoPackageRepository;
import com.bovina.embryology.application.EmbryoCryopreservationBoundary;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import com.bovina.platform.application.StableIds;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbryoPackages {
  private final TenantAccess access;
  private final OpuFacilities facilities;
  private final EmbryoCryopreservationBoundary embryos;
  private final CryostorageFacts facts;
  private final EmbryoPackageRepository packages;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public EmbryoPackages(
      TenantAccess access,
      OpuFacilities facilities,
      EmbryoCryopreservationBoundary embryos,
      CryostorageFacts facts,
      EmbryoPackageRepository packages,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.facilities = facilities;
    this.embryos = embryos;
    this.facts = facts;
    this.packages = packages;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public View create(ExecutionContext c, UUID key, EmbryoPackage.Registration input) {
    access.require(c, "inventory:write");
    if (!key.equals(input.id())) throw rejected("PACKAGE_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "CREATE_EMBRYO_PACKAGE_V1",
        input,
        View.class,
        () -> {
          facilities.requireDestination(c.tenantId(), input.establishmentId(), null);
          var p =
              packages.save(new EmbryoPackage(c.tenantId(), input, c.actorId(), clock.instant()));
          packages.flush();
          record(c, p, "CREATE");
          return view(p);
        });
  }

  @Transactional
  public View addMembers(ExecutionContext c, UUID key, UUID packageId, AddMembers command) {
    access.require(c, "inventory:write");
    return receipts.replayOrExecute(
        c,
        key,
        "ADD_PACKAGE_MEMBERS_V1",
        new AddIntent(packageId, command),
        View.class,
        () -> {
          var p = lock(c.tenantId(), packageId);
          requireDraft(p, command.expectedVersion());
          if (command.items() == null || command.items().isEmpty() || command.items().size() > 100)
            throw rejected("INVALID_PACKAGE_ITEMS");
          if (new HashSet<>(command.items().stream().map(Item::cryopreservationItemId).toList())
                  .size()
              != command.items().size()) throw rejected("DUPLICATE_CRYOPRESERVATION_ITEM");
          var itemIds = command.items().stream().map(Item::cryopreservationItemId).toList();
          var cryoItems = facts.preservedItems(c.tenantId(), itemIds);
          if (cryoItems.size() != itemIds.size()) throw rejected("CRYOPRESERVATION_ITEM_NOT_FOUND");
          var members =
              command.items().stream()
                  .map(
                      i -> {
                        StableIds.requireVersion7(i.id());
                        var preserved = cryoItems.get(i.cryopreservationItemId());
                        if (!preserved.establishmentId().equals(p.establishmentId()))
                          throw rejected("PACKAGE_ESTABLISHMENT_MISMATCH");
                        return new PackageMember(
                            i.id(), preserved.id(), preserved.embryoId(), i.positionCode());
                      })
                  .toList();
          var existing = facts.activeMembers(c.tenantId(), p.id());
          var all = new java.util.ArrayList<>(existing);
          all.addAll(members);
          var allEmbryos =
              embryos.lockPackageMembers(
                  c.tenantId(), all.stream().map(PackageMember::embryoId).toList());
          // Mixed custody is deliberately not certified before package policy validation.
          if (allEmbryos.values().stream()
                      .map(EmbryoCryopreservationBoundary.Member::ownerId)
                      .distinct()
                      .count()
                  > 1
              || allEmbryos.values().stream()
                      .map(EmbryoCryopreservationBoundary.Member::matingId)
                      .distinct()
                      .count()
                  > 1) throw rejected("MIXED_PACKAGE_REQUIRES_DOMAIN_VALIDATION");
          if (p.ownerPartyId() != null
              && allEmbryos.values().stream().anyMatch(e -> !p.ownerPartyId().equals(e.ownerId())))
            throw rejected("PACKAGE_OWNER_MISMATCH");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          facts.insertPackageMembers(c.tenantId(), p.id(), members, c.actorId(), now);
          p.membersAdded(now);
          packages.flush();
          record(c, p, "ADD_MEMBERS");
          return view(p);
        });
  }

  @Transactional
  public View seal(ExecutionContext c, UUID key, UUID packageId, long expectedVersion) {
    access.require(c, "inventory:write");
    return receipts.replayOrExecute(
        c,
        key,
        "SEAL_EMBRYO_PACKAGE_V1",
        new SealIntent(packageId, expectedVersion),
        View.class,
        () -> {
          var p = lock(c.tenantId(), packageId);
          if (p.version() != expectedVersion) throw conflict("STALE_PACKAGE_VERSION");
          p.seal(facts.activeMemberCount(c.tenantId(), packageId), c.actorId(), clock.instant());
          packages.flush();
          record(c, p, "SEAL");
          return view(p);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "inventory:read");
    return view(
        packages.findByOrganizationIdAndId(c.tenantId(), id).orElseThrow(EmbryoPackages::missing));
  }

  @Transactional(readOnly = true)
  public PageResult<View> page(ExecutionContext c, SearchPage page) {
    access.require(c, "inventory:read");
    return new PageResult<>(
        packages
            .findByOrganizationId(
                c.tenantId(), PageRequest.of(page.page(), page.size(), Sort.by("id")))
            .stream()
            .map(EmbryoPackages::view)
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional(readOnly = true)
  public List<PackageMember> members(ExecutionContext c, UUID id) {
    get(c, id);
    return facts.activeMembers(c.tenantId(), id);
  }

  private EmbryoPackage lock(UUID tenant, UUID id) {
    return packages.lock(tenant, id).orElseThrow(EmbryoPackages::missing);
  }

  private static void requireDraft(EmbryoPackage p, long expected) {
    if (p.version() != expected) throw conflict("STALE_PACKAGE_VERSION");
    if (p.status() != EmbryoPackage.Status.DRAFT) throw conflict("PACKAGE_MEMBERSHIP_FINALIZED");
  }

  private void record(ExecutionContext c, EmbryoPackage p, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant().truncatedTo(ChronoUnit.MICROS),
            action,
            "EMBRYO_PACKAGE",
            p.id(),
            p.version(),
            null,
            null,
            p.status().name()));
  }

  public static View view(EmbryoPackage p) {
    return new View(
        p.id(),
        p.establishmentId(),
        p.packageCode(),
        p.packagingType(),
        p.ownerPartyId(),
        p.status(),
        p.sealedAt(),
        p.currentLocationId(),
        p.lastMovementSequence(),
        p.version());
  }

  public record View(
      UUID id,
      UUID establishmentId,
      String packageCode,
      String packagingType,
      UUID ownerPartyId,
      EmbryoPackage.Status status,
      java.time.Instant sealedAt,
      UUID currentLocationId,
      long lastMovementSequence,
      long version) {}

  public record Item(UUID id, UUID cryopreservationItemId, String positionCode) {}

  public record AddMembers(long expectedVersion, List<Item> items) {
    public AddMembers {
      if (items != null) items = List.copyOf(items);
    }
  }

  private record AddIntent(UUID packageId, AddMembers command) {}

  private record SealIntent(UUID packageId, long expectedVersion) {}

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Package request is not valid");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, code, "Package has changed");
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "PACKAGE_NOT_FOUND", "Package not found");
  }
}
