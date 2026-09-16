package com.bovina.operations.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.LocalDate;
import java.util.UUID;

public record ResponsibleTechnicianAssignment(
    UUID id,
    UUID establishmentId,
    UUID professionalId,
    UUID credentialId,
    String credentialIssuer,
    String credentialJurisdiction,
    String credentialNumber,
    UUID documentId,
    EffectivePeriod period,
    long version) {
  public ResponsibleTechnicianAssignment {
    StableIds.requireVersion7(id);
    if (establishmentId == null
        || professionalId == null
        || credentialId == null
        || credentialIssuer == null
        || credentialNumber == null
        || period == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_TECHNICIAN_ASSIGNMENT",
          "Assignment requires establishment, professional, credential and period");
  }

  public ResponsibleTechnicianAssignment end(LocalDate until, long expected) {
    if (until == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_ASSIGNMENT_END", "An end date is required");
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Assignment has changed");
    if (period.until() != null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "ASSIGNMENT_ALREADY_ENDED",
          "An ended assignment cannot be rewritten");
    return new ResponsibleTechnicianAssignment(
        id,
        establishmentId,
        professionalId,
        credentialId,
        credentialIssuer,
        credentialJurisdiction,
        credentialNumber,
        documentId,
        new EffectivePeriod(period.from(), until),
        version + 1);
  }
}
