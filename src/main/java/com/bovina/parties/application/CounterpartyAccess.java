package com.bovina.parties.application;

import com.bovina.parties.domain.CounterpartyRole;
import com.bovina.parties.infrastructure.*;
import com.bovina.platform.application.ApplicationFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class CounterpartyAccess {
  private final PartyRepository parties;
  private final CounterpartyQueries queries;

  public CounterpartyAccess(PartyRepository parties, CounterpartyQueries queries) {
    this.parties = parties;
    this.queries = queries;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireActive(UUID tenant, UUID id) {
    parties
        .lock(tenant, id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND,
                    "COUNTERPARTY_NOT_FOUND",
                    "Counterparty not found"))
        .requireActive();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireAnimalOwner(UUID tenant, UUID id) {
    requireActive(tenant, id);
    if (!queries.hasRole(tenant, id, CounterpartyRole.ANIMAL_OWNER))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "ANIMAL_OWNER_REQUIRED",
          "Register this counterparty as an animal owner first");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireClient(UUID tenant, UUID id) {
    requireActive(tenant, id);
    if (!queries.hasRole(tenant, id, CounterpartyRole.CLIENT))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "CLIENT_REQUIRED",
          "Register this counterparty as a client first");
  }
}
