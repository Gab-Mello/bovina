package com.bovina.parties.application;

import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.domain.FarmProperty;
import com.bovina.parties.infrastructure.FarmPropertyRepository;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FarmProperties {
  private final TenantAccess access;
  private final CounterpartyAccess counterparties;
  private final FarmPropertyRepository properties;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public FarmProperties(
      TenantAccess access,
      CounterpartyAccess counterparties,
      FarmPropertyRepository properties,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.counterparties = counterparties;
    this.properties = properties;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public View register(ExecutionContext context, UUID key, FarmProperty.Details input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_PROPERTY_V1",
        input,
        View.class,
        () -> {
          if (input.ownerId() != null)
            counterparties.requireAnimalOwner(context.tenantId(), input.ownerId());
          if (input.operatorId() != null)
            counterparties.requireActive(context.tenantId(), input.operatorId());
          var property =
              properties.save(
                  new FarmProperty(context.tenantId(), input, context.actorId(), clock.instant()));
          properties.flush();
          record(context, property, "CREATE");
          return view(property);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext context, UUID id) {
    access.require(context, "master-data:read");
    return view(find(context, id));
  }

  @Transactional(readOnly = true)
  public FarmOrigin origin(ExecutionContext context, UUID id, boolean requireActive) {
    access.require(context, "master-data:read");
    var property = find(context, id);
    if (requireActive && !property.status().equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "PROPERTY_ARCHIVED", "Property is archived");
    var d = property.details();
    return new FarmOrigin(
        id,
        d.name(),
        d.address(),
        d.municipalityCode(),
        d.internalCode(),
        d.ownerId(),
        d.operatorId(),
        property.version());
  }

  @Transactional(readOnly = true)
  public PageResult<View> search(ExecutionContext context, SearchPage page) {
    access.require(context, "master-data:read");
    return new PageResult<>(
        properties
            .search(context.tenantId(), page.pattern(), PageRequest.of(page.page(), page.size()))
            .stream()
            .map(FarmProperties::view)
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional
  public View archive(ExecutionContext context, UUID key, UUID id, long expectedVersion) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "ARCHIVE_PROPERTY_V1",
        new Archive(id, expectedVersion),
        View.class,
        () -> {
          var p = find(context, id);
          p.archive(expectedVersion);
          properties.flush();
          record(context, p, "ARCHIVE");
          return view(p);
        });
  }

  private FarmProperty find(ExecutionContext c, UUID id) {
    return properties
        .findByOrganizationIdAndId(c.tenantId(), id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND, "PROPERTY_NOT_FOUND", "Property not found"));
  }

  private void record(ExecutionContext c, FarmProperty p, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "FARM_PROPERTY",
            p.id(),
            p.version(),
            null,
            null,
            p.status()));
  }

  private static View view(FarmProperty p) {
    return new View(p.details(), p.status(), p.version());
  }

  public record View(FarmProperty.Details details, String status, long version) {}

  private record Archive(UUID id, long expectedVersion) {}
}
