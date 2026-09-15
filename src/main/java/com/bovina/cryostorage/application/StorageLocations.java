package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.infrastructure.StorageLocationStore;
import com.bovina.cryostorage.infrastructure.StorageLocationStore.Location;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import com.bovina.platform.application.StableIds;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageLocations {
  private final TenantAccess access;
  private final OpuFacilities facilities;
  private final StorageLocationStore store;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public StorageLocations(
      TenantAccess access,
      OpuFacilities facilities,
      StorageLocationStore store,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.facilities = facilities;
    this.store = store;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Location register(ExecutionContext c, UUID key, Registration input) {
    access.require(c, "inventory:write");
    validate(input);
    if (!key.equals(input.id())) throw rejected("LOCATION_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_STORAGE_LOCATION_V1",
        input,
        Location.class,
        () -> {
          facilities.requireDestination(
              c.tenantId(),
              input.establishmentId(),
              input.parentId() == null ? input.operationalLocationId() : null);
          if (input.parentId() != null) {
            var parent = store.lock(c.tenantId(), input.parentId());
            if (parent == null
                || !parent.establishmentId().equals(input.establishmentId())
                || !parent.status().equals("ACTIVE")) throw rejected("INVALID_LOCATION_PARENT");
          }
          store.insert(
              c,
              new Location(
                  input.id(),
                  input.establishmentId(),
                  input.operationalLocationId(),
                  input.parentId(),
                  input.typeCode(),
                  input.code(),
                  input.name(),
                  "ACTIVE",
                  0),
              clock.instant());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  clock.instant(),
                  "REGISTER",
                  "STORAGE_LOCATION",
                  input.id(),
                  0L,
                  null,
                  null,
                  "ACTIVE"));
          return new Location(
              input.id(),
              input.establishmentId(),
              input.operationalLocationId(),
              input.parentId(),
              input.typeCode(),
              input.code(),
              input.name(),
              "ACTIVE",
              0);
        });
  }

  @Transactional
  public Location deactivate(ExecutionContext c, UUID key, UUID id, long expected) {
    access.require(c, "inventory:write");
    return receipts.replayOrExecute(
        c,
        key,
        "DEACTIVATE_STORAGE_LOCATION_V1",
        new Deactivate(id, expected),
        Location.class,
        () -> {
          var location = store.lock(c.tenantId(), id);
          if (location == null) throw missing();
          if (location.version() != expected) throw conflict("STALE_LOCATION_VERSION");
          if (store.hasMaterialOrChildren(c.tenantId(), id))
            throw conflict("STORAGE_LOCATION_IN_USE");
          if (store.deactivate(c.tenantId(), id, expected) != 1)
            throw conflict("STORAGE_LOCATION_NOT_ACTIVE");
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  clock.instant(),
                  "DEACTIVATE",
                  "STORAGE_LOCATION",
                  id,
                  expected + 1,
                  null,
                  "ACTIVE",
                  "INACTIVE"));
          return store.find(c.tenantId(), id);
        });
  }

  @Transactional(readOnly = true)
  public Location get(ExecutionContext c, UUID id) {
    access.require(c, "inventory:read");
    var location = store.find(c.tenantId(), id);
    if (location == null) throw missing();
    return location;
  }

  @Transactional(readOnly = true)
  public PageResult<Location> page(ExecutionContext c, SearchPage page) {
    access.require(c, "inventory:read");
    return new PageResult<>(
        store.page(c.tenantId(), page.page(), page.size()), page.page(), page.size());
  }

  private static void validate(Registration input) {
    StableIds.requireVersion7(input.id());
    StableIds.requireVersion7(input.establishmentId());
    if (input.parentId() != null) StableIds.requireVersion7(input.parentId());
    if (input.operationalLocationId() != null)
      StableIds.requireVersion7(input.operationalLocationId());
    if (input.parentId() != null && input.operationalLocationId() != null
        || input.typeCode() == null
        || !input.typeCode().matches("[A-Z][A-Z0-9_]{0,79}")
        || input.code() == null
        || input.code().isBlank()
        || input.code().length() > 160
        || input.name() != null && input.name().length() > 200)
      throw rejected("INVALID_STORAGE_LOCATION");
  }

  private record Deactivate(UUID id, long expectedVersion) {}

  public record Registration(
      UUID id,
      UUID establishmentId,
      UUID operationalLocationId,
      UUID parentId,
      String typeCode,
      String code,
      String name) {}

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Storage location is invalid");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Storage location has changed");
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND,
        "STORAGE_LOCATION_NOT_FOUND",
        "Storage location not found");
  }
}
