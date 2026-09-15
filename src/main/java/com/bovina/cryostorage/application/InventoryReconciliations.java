package com.bovina.cryostorage.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.cryostorage.infrastructure.InventoryStore;
import com.bovina.cryostorage.infrastructure.ReconciliationStore;
import com.bovina.cryostorage.infrastructure.ReconciliationStore.Discrepancy;
import com.bovina.cryostorage.infrastructure.ReconciliationStore.Observation;
import com.bovina.cryostorage.infrastructure.ReconciliationStore.Session;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReconciliations {
  private final TenantAccess access;
  private final OpuFacilities facilities;
  private final ReconciliationStore store;
  private final InventoryStore inventory;
  private final InventoryMovements movements;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public InventoryReconciliations(
      TenantAccess access,
      OpuFacilities facilities,
      ReconciliationStore store,
      InventoryStore inventory,
      InventoryMovements movements,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.facilities = facilities;
    this.store = store;
    this.inventory = inventory;
    this.movements = movements;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Session open(ExecutionContext c, UUID key, Open input) {
    access.require(c, "inventory:write");
    StableIds.requireVersion7(input.id());
    if (!key.equals(input.id())
        || input.method() == null
        || !List.of("SCAN", "MANUAL", "MIXED").contains(input.method())
        || input.notes() != null && input.notes().length() > 1000)
      throw rejected("INVALID_RECONCILIATION_SESSION");
    return receipts.replayOrExecute(
        c,
        key,
        "OPEN_RECONCILIATION_V1",
        input,
        Session.class,
        () -> {
          facilities.requireDestination(c.tenantId(), input.establishmentId(), null);
          if (input.scopeLocationId() != null
              && !inventory.activeLocation(
                  c.tenantId(), input.establishmentId(), input.scopeLocationId()))
            throw rejected("RECONCILIATION_SCOPE_INACTIVE");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.open(
              c,
              new Session(
                  input.id(),
                  input.establishmentId(),
                  input.scopeLocationId(),
                  input.method(),
                  "OPEN",
                  0,
                  input.notes()),
              now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "OPEN",
                  "INVENTORY_RECONCILIATION",
                  input.id(),
                  0L,
                  null,
                  null,
                  "OPEN"));
          return new Session(
              input.id(),
              input.establishmentId(),
              input.scopeLocationId(),
              input.method(),
              "OPEN",
              0,
              input.notes());
        });
  }

  @Transactional
  public ObservationBatch.Result observe(
      ExecutionContext c, UUID key, UUID id, ObservationBatch batch) {
    access.require(c, "inventory:write");
    if (!key.equals(batch.batchId())) throw rejected("OBSERVATION_BATCH_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_RECONCILIATION_OBSERVATIONS_V1",
        new ObservationIntent(id, batch),
        ObservationBatch.Result.class,
        () -> {
          var session = find(c, id, true);
          if (!session.status().equals("OPEN")) throw conflict("RECONCILIATION_CLOSED");
          for (var o : batch.observations()) {
            if (o.observedLocationId() != null
                && !inventory.activeLocation(
                    c.tenantId(), session.establishmentId(), o.observedLocationId()))
              throw rejected("OBSERVED_LOCATION_INACTIVE");
          }
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.observe(c, id, batch.observations(), now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "OBSERVE",
                  "INVENTORY_RECONCILIATION",
                  id,
                  session.version(),
                  null,
                  null,
                  "items=" + batch.observations().size()));
          return new ObservationBatch.Result(
              batch.batchId(), batch.observations().stream().map(Observation::id).toList());
        });
  }

  @Transactional
  public Session close(ExecutionContext c, UUID key, UUID id, long expectedVersion) {
    access.require(c, "inventory:write");
    return receipts.replayOrExecute(
        c,
        key,
        "CLOSE_RECONCILIATION_V1",
        new CloseIntent(id, expectedVersion),
        Session.class,
        () -> {
          var session = find(c, id, true);
          if (!session.status().equals("OPEN") || session.version() != expectedVersion)
            throw conflict("RECONCILIATION_NOT_OPEN_AT_VERSION");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.close(c, id, expectedVersion, now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "CLOSE",
                  "INVENTORY_RECONCILIATION",
                  id,
                  expectedVersion + 1,
                  null,
                  "OPEN",
                  "CLOSED"));
          return store.find(c.tenantId(), id, false);
        });
  }

  @Transactional
  public InventoryMovements.Result resolve(
      ExecutionContext c, UUID key, UUID id, InventoryMovements.Intent movement) {
    access.require(c, "inventory:adjust");
    var session = find(c, id, false);
    if (!session.status().equals("CLOSED")) throw conflict("RECONCILIATION_NOT_CLOSED");
    if (!store.hasDifference(c.tenantId(), id, movement.packageId()))
      throw rejected("RECONCILIATION_PACKAGE_NOT_DISCREPANT");
    return movements.resolve(c, key, movement, id);
  }

  @Transactional(readOnly = true)
  public PageResult<Discrepancy> differences(ExecutionContext c, UUID id, SearchPage page) {
    access.require(c, "inventory:read");
    find(c, id, false);
    return new PageResult<>(store.differences(c.tenantId(), id, page), page.page(), page.size());
  }

  private Session find(ExecutionContext c, UUID id, boolean lock) {
    var session = store.find(c.tenantId(), id, lock);
    if (session == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND,
          "RECONCILIATION_NOT_FOUND",
          "Reconciliation not found");
    return session;
  }

  public record ObservationBatch(UUID batchId, List<Observation> observations) {
    public ObservationBatch {
      StableIds.requireVersion7(batchId);
      if (observations == null || observations.isEmpty() || observations.size() > 100)
        throw rejected("INVALID_OBSERVATION_BATCH");
      observations = List.copyOf(observations);
      if (new HashSet<>(observations.stream().map(Observation::id).toList()).size()
          != observations.size()) throw rejected("DUPLICATE_OBSERVATION_ID");
      for (var o : observations) {
        StableIds.requireVersion7(o.id());
        if (o.packageId() != null) StableIds.requireVersion7(o.packageId());
        if (o.packageId() == null && (o.rawIdentifier() == null || o.rawIdentifier().isBlank())
            || o.observedAt() == null
            || o.notes() != null && o.notes().length() > 1000)
          throw rejected("INVALID_RECONCILIATION_OBSERVATION");
      }
    }

    public record Result(UUID batchId, List<UUID> observationIds) {
      public Result {
        observationIds = List.copyOf(observationIds);
      }
    }
  }

  public record Open(
      UUID id, UUID establishmentId, UUID scopeLocationId, String method, String notes) {}

  private record ObservationIntent(UUID id, ObservationBatch batch) {}

  private record CloseIntent(UUID id, long version) {}

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Reconciliation request is invalid");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Reconciliation state changed");
  }
}
