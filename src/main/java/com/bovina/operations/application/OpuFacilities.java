package com.bovina.operations.application;

import com.bovina.operations.infrastructure.*;
import com.bovina.platform.application.ApplicationFailure;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OpuFacilities {
  private final EstablishmentRepository establishments;
  private final OperationalLocationStore locations;
  private final ProfessionalStore professionals;

  public OpuFacilities(
      EstablishmentRepository establishments,
      OperationalLocationStore locations,
      ProfessionalStore professionals) {
    this.establishments = establishments;
    this.locations = locations;
    this.professionals = professionals;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireDestination(UUID tenant, UUID establishment, UUID location) {
    establishments
        .lock(tenant, establishment)
        .orElseThrow(() -> missing("ESTABLISHMENT"))
        .requireActive();
    if (location != null) {
      var l =
          locations.find(tenant, establishment, location).orElseThrow(() -> missing("LOCATION"));
      if (!l.status().equals("ACTIVE"))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.CONFLICT, "LOCATION_INACTIVE", "Location is inactive");
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireProfessional(UUID tenant, UUID id) {
    var p = professionals.lock(tenant, id).orElseThrow(() -> missing("PROFESSIONAL"));
    if (!p.status().equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "PROFESSIONAL_INACTIVE", "Professional is inactive");
  }

  private static ApplicationFailure missing(String type) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, type + "_NOT_FOUND", "Operational reference not found");
  }
}
