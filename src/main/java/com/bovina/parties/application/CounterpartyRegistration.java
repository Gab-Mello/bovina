package com.bovina.parties.application;

import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.domain.*;
import com.bovina.parties.infrastructure.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CounterpartyRegistration {
  private final TenantAccess access;
  private final PartyRepository parties;
  private final CounterpartyQueries queries;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public CounterpartyRegistration(
      TenantAccess access,
      PartyRepository parties,
      CounterpartyQueries queries,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.parties = parties;
    this.queries = queries;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public CounterpartyView registerOwner(
      ExecutionContext context, UUID key, RegisterCounterparty input, OwnerScope scope) {
    if (scope == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "OWNER_SCOPE_REQUIRED",
          "Specify animal or material ownership");
    return register(
        context,
        key,
        input,
        scope == OwnerScope.ANIMAL
            ? CounterpartyRole.ANIMAL_OWNER
            : CounterpartyRole.MATERIAL_OWNER);
  }

  @Transactional
  public CounterpartyView registerSupplier(
      ExecutionContext context, UUID key, RegisterCounterparty input) {
    return register(context, key, input, CounterpartyRole.SUPPLIER);
  }

  @Transactional
  public CounterpartyView registerShipmentRecipient(
      ExecutionContext context, UUID key, RegisterCounterparty input) {
    return register(context, key, input, CounterpartyRole.SHIPMENT_DESTINATION);
  }

  @Transactional
  public CounterpartyView registerClient(
      ExecutionContext context, UUID key, RegisterCounterparty input) {
    return register(context, key, input, CounterpartyRole.CLIENT);
  }

  private CounterpartyView register(
      ExecutionContext context, UUID key, RegisterCounterparty input, CounterpartyRole role) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_" + role.name() + "_V1",
        input,
        CounterpartyView.class,
        () -> {
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          var existing = parties.lock(context.tenantId(), input.id());
          Party party;
          if (existing.isPresent()) {
            party = existing.orElseThrow();
            party.requireActive();
            if (input.expectedVersion() == null)
              throw new ApplicationFailure(
                  ApplicationFailure.Kind.CONFLICT,
                  "EXISTING_COUNTERPARTY_VERSION_REQUIRED",
                  "Supply the existing counterparty version to reuse this identity");
            party.requireVersion(input.expectedVersion());
            if (party.type() != input.type() || !party.displayName().equals(input.displayName()))
              throw new ApplicationFailure(
                  ApplicationFailure.Kind.CONFLICT,
                  "COUNTERPARTY_IDENTITY_MISMATCH",
                  "Update the existing identity explicitly before assigning a role");
          } else {
            if (input.expectedVersion() != null) throw missing();
            party =
                parties.save(
                    Party.registerClient(
                        input.id(),
                        context.tenantId(),
                        input.type(),
                        input.displayName(),
                        input.occurredAt(),
                        DataProvenance.manual(context.actorId(), now)));
            parties.flush();
          }
          if (!queries.hasRole(context.tenantId(), party.id(), role)) {
            queries.attachRole(context.tenantId(), party.id(), role);
            audit.record(
                new AuditEvent(
                    ids.next(),
                    context,
                    now,
                    "REGISTER",
                    role.name(),
                    party.id(),
                    party.version(),
                    null,
                    null,
                    "ACTIVE"));
          }
          return view(party);
        });
  }

  @Transactional(readOnly = true)
  public CounterpartyView get(ExecutionContext context, UUID id, CounterpartyRole role) {
    access.require(context, "master-data:read");
    var party =
        parties
            .findByOrganizationIdAndId(context.tenantId(), id)
            .orElseThrow(CounterpartyRegistration::missing);
    if (!queries.hasRole(context.tenantId(), id, role)) throw missing();
    return view(party);
  }

  @Transactional(readOnly = true)
  public PageResult<CounterpartyView> search(
      ExecutionContext context, CounterpartyRole role, SearchPage page) {
    access.require(context, "master-data:read");
    return new PageResult<>(
        queries.search(context.tenantId(), role, page), page.page(), page.size());
  }

  @Transactional
  public CounterpartyView updateClient(
      ExecutionContext context, UUID key, UUID id, UpdateClient input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "UPDATE_CLIENT_V1",
        new UpdateIntent(id, input),
        CounterpartyView.class,
        () -> {
          var party =
              parties
                  .findByOrganizationIdAndId(context.tenantId(), id)
                  .orElseThrow(CounterpartyRegistration::missing);
          if (!queries.hasRole(context.tenantId(), id, CounterpartyRole.CLIENT)) throw missing();
          party.updateDetails(
              input.expectedVersion(), input.displayName(), input.legalName(), input.address());
          parties.flush();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  context,
                  clock.instant(),
                  "UPDATE",
                  "CLIENT",
                  id,
                  party.version(),
                  null,
                  null,
                  party.status()));
          return view(party);
        });
  }

  @Transactional
  public CounterpartyView archive(
      ExecutionContext context, UUID key, UUID id, CounterpartyRole role, long expectedVersion) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "ARCHIVE_COUNTERPARTY_V1",
        new ArchiveIntent(id, role, expectedVersion),
        CounterpartyView.class,
        () -> {
          var party =
              parties
                  .findByOrganizationIdAndId(context.tenantId(), id)
                  .orElseThrow(CounterpartyRegistration::missing);
          if (!queries.hasRole(context.tenantId(), id, role)) throw missing();
          party.archive(expectedVersion);
          parties.flush();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  context,
                  clock.instant(),
                  "ARCHIVE",
                  role.name(),
                  id,
                  party.version(),
                  null,
                  "ACTIVE",
                  "ARCHIVED"));
          return view(party);
        });
  }

  private static CounterpartyView view(Party p) {
    return new CounterpartyView(
        p.id(), p.type(), p.displayName(), p.legalName(), p.address(), p.status(), p.version());
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "COUNTERPARTY_NOT_FOUND", "Counterparty not found");
  }

  public enum OwnerScope {
    ANIMAL,
    MATERIAL
  }

  public record UpdateClient(
      long expectedVersion, String displayName, String legalName, Address address) {}

  private record UpdateIntent(UUID id, UpdateClient input) {}

  private record ArchiveIntent(UUID id, CounterpartyRole role, long expectedVersion) {}
}
