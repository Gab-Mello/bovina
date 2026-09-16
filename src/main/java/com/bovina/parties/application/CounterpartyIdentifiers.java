package com.bovina.parties.application;

import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.domain.*;
import com.bovina.parties.infrastructure.*;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CounterpartyIdentifiers {
  private final TenantAccess access;
  private final PartyRepository parties;
  private final CounterpartyQueries queries;
  private final CounterpartyIdentifierStore identifiers;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public CounterpartyIdentifiers(
      TenantAccess access,
      PartyRepository parties,
      CounterpartyQueries queries,
      CounterpartyIdentifierStore identifiers,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.parties = parties;
    this.queries = queries;
    this.identifiers = identifiers;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public CounterpartyIdentifier register(
      ExecutionContext c,
      UUID key,
      UUID counterparty,
      CounterpartyRole role,
      CounterpartyIdentifier input) {
    access.require(c, "master-data:write");
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_COUNTERPARTY_IDENTIFIER_V1",
        new Intent(counterparty, role, input),
        CounterpartyIdentifier.class,
        () -> {
          parties
              .lock(c.tenantId(), counterparty)
              .orElseThrow(CounterpartyIdentifiers::missing)
              .requireActive();
          requireRole(c, counterparty, role);
          identifiers.insert(c.tenantId(), counterparty, input, c.actorId(), clock.instant());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  clock.instant(),
                  "REGISTER",
                  "COUNTERPARTY_IDENTIFIER",
                  input.id(),
                  null,
                  null,
                  null,
                  "RECORDED"));
          return input;
        });
  }

  @Transactional(readOnly = true)
  public PageResult<CounterpartyIdentifier> list(
      ExecutionContext c, UUID counterparty, CounterpartyRole role, SearchPage page) {
    access.require(c, "master-data:read");
    requireRole(c, counterparty, role);
    return new PageResult<>(
        identifiers.list(c.tenantId(), counterparty, page), page.page(), page.size());
  }

  private void requireRole(ExecutionContext c, UUID id, CounterpartyRole role) {
    if (!queries.hasRole(c.tenantId(), id, role)) throw missing();
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "COUNTERPARTY_NOT_FOUND", "Counterparty not found");
  }

  private record Intent(UUID counterparty, CounterpartyRole role, CounterpartyIdentifier input) {}
}
