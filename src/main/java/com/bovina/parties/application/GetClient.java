package com.bovina.parties.application;

import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.infrastructure.ClientCommandStore;
import com.bovina.parties.infrastructure.PartyRepository;
import com.bovina.platform.application.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetClient {
  private final TenantAccess access;
  private final PartyRepository parties;
  private final ClientCommandStore clients;

  public GetClient(TenantAccess access, PartyRepository parties, ClientCommandStore clients) {
    this.access = access;
    this.parties = parties;
    this.clients = clients;
  }

  @Transactional(readOnly = true)
  public ClientView get(ExecutionContext context, UUID id) {
    access.require(context, "client:read");
    var party =
        parties
            .findByOrganizationIdAndId(context.tenantId(), id)
            .filter(p -> clients.isClient(context.tenantId(), id))
            .orElseThrow(
                () ->
                    new ApplicationFailure(
                        ApplicationFailure.Kind.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"));
    var source = party.provenance();
    return new ClientView(
        party.id(),
        party.type(),
        party.displayName(),
        party.version(),
        source.originType().name(),
        source.recordedByUserId(),
        source.recordedAt());
  }
}
