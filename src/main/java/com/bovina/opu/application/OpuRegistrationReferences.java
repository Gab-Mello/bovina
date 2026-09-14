package com.bovina.opu.application;

import com.bovina.operations.application.OpuFacilities;
import com.bovina.opu.domain.OpuSession;
import com.bovina.parties.application.*;
import com.bovina.platform.application.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class OpuRegistrationReferences {
  private final OpuFacilities facilities;
  private final FarmProperties farms;
  private final CounterpartyAccess clients;

  public OpuRegistrationReferences(
      OpuFacilities facilities, FarmProperties farms, CounterpartyAccess clients) {
    this.facilities = facilities;
    this.farms = farms;
    this.clients = clients;
  }

  public void validate(ExecutionContext c, OpuSession.Registration r) {
    facilities.requireDestination(c.tenantId(), r.establishmentId(), r.operationalLocationId());
    facilities.requireProfessional(c.tenantId(), r.leadProfessionalId());
    farms.origin(c, r.farmPropertyId(), true);
    if (r.clientId() != null) clients.requireClient(c.tenantId(), r.clientId());
  }
}
