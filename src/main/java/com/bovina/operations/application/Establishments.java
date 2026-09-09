package com.bovina.operations.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.domain.*;
import com.bovina.operations.infrastructure.*;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Establishments {
  private final TenantAccess access;
  private final EstablishmentRepository establishments;
  private final OperationalLocationStore locations;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public Establishments(
      TenantAccess access,
      EstablishmentRepository establishments,
      OperationalLocationStore locations,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.establishments = establishments;
    this.locations = locations;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public View register(ExecutionContext context, UUID key, Establishment.Details input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_ESTABLISHMENT_V1",
        input,
        View.class,
        () -> {
          if (input.registrationDocumentId() != null)
            documents.requireReference(context.tenantId(), input.registrationDocumentId());
          var e =
              establishments.save(
                  new Establishment(context.tenantId(), input, context.actorId(), clock.instant()));
          establishments.flush();
          record(context, "CREATE", "ESTABLISHMENT", e.id(), e.version(), e.status());
          return view(e);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext context, UUID id) {
    access.require(context, "master-data:read");
    return view(find(context, id));
  }

  @Transactional(readOnly = true)
  public PageResult<View> search(ExecutionContext context, SearchPage page) {
    access.require(context, "master-data:read");
    return new PageResult<>(
        establishments
            .search(context.tenantId(), page.pattern(), PageRequest.of(page.page(), page.size()))
            .stream()
            .map(Establishments::view)
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional
  public View deactivate(ExecutionContext context, UUID key, UUID id, long expected) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "DEACTIVATE_ESTABLISHMENT_V1",
        new Deactivate(id, expected),
        View.class,
        () -> {
          var e = find(context, id);
          e.deactivate(expected);
          establishments.flush();
          record(context, "DEACTIVATE", "ESTABLISHMENT", id, e.version(), e.status());
          return view(e);
        });
  }

  @Transactional
  public OperationalLocation registerLocation(
      ExecutionContext context, UUID key, UUID establishment, RegisterLocation input) {
    access.require(context, "master-data:write");
    var location =
        new OperationalLocation(
            input.id(), establishment, input.name(), input.type(), input.timezone(), "ACTIVE", 0);
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_LOCATION_V1",
        location,
        OperationalLocation.class,
        () -> {
          establishments
              .lock(context.tenantId(), establishment)
              .orElseThrow(Establishments::missing)
              .requireActive();
          locations.insert(context.tenantId(), location, context.actorId(), clock.instant());
          record(context, "CREATE", "OPERATIONAL_LOCATION", location.id(), 0, "ACTIVE");
          return location;
        });
  }

  @Transactional(readOnly = true)
  public OperationalLocation getLocation(ExecutionContext context, UUID establishment, UUID id) {
    access.require(context, "master-data:read");
    return location(context, establishment, id);
  }

  @Transactional(readOnly = true)
  public PageResult<OperationalLocation> locations(
      ExecutionContext context, UUID establishment, SearchPage page) {
    access.require(context, "master-data:read");
    find(context, establishment);
    return new PageResult<>(
        locations.search(context.tenantId(), establishment, page), page.page(), page.size());
  }

  @Transactional
  public OperationalLocation deactivateLocation(
      ExecutionContext context, UUID key, UUID establishment, UUID id, long expected) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "DEACTIVATE_LOCATION_V1",
        new LocationChange(establishment, id, expected),
        OperationalLocation.class,
        () -> {
          var l = location(context, establishment, id).deactivate(expected);
          locations.deactivate(context.tenantId(), l);
          record(context, "DEACTIVATE", "OPERATIONAL_LOCATION", id, l.version(), l.status());
          return l;
        });
  }

  private Establishment find(ExecutionContext c, UUID id) {
    return establishments
        .findByOrganizationIdAndId(c.tenantId(), id)
        .orElseThrow(Establishments::missing);
  }

  private OperationalLocation location(ExecutionContext c, UUID e, UUID id) {
    return locations
        .find(c.tenantId(), e, id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND, "LOCATION_NOT_FOUND", "Location not found"));
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "ESTABLISHMENT_NOT_FOUND", "Establishment not found");
  }

  private static View view(Establishment e) {
    return new View(e.details(), e.status(), e.version());
  }

  private void record(
      ExecutionContext c, String action, String type, UUID id, long version, String status) {
    audit.record(
        new AuditEvent(
            ids.next(), c, clock.instant(), action, type, id, version, null, null, status));
  }

  public record View(Establishment.Details details, String status, long version) {}

  public record RegisterLocation(
      UUID id, String name, OperationalLocation.Type type, String timezone) {}

  private record Deactivate(UUID id, long expectedVersion) {}

  private record LocationChange(UUID establishment, UUID id, long expectedVersion) {}
}
