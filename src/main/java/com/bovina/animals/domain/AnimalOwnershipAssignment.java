package com.bovina.animals.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.LocalDate;
import java.util.UUID;

public record AnimalOwnershipAssignment(
    UUID id,
    UUID animalId,
    UUID ownerId,
    EffectivePeriod period,
    UUID sourceDocumentId,
    long version) {
  public AnimalOwnershipAssignment {
    StableIds.requireVersion7(id);
    if (animalId == null || ownerId == null || period == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_OWNERSHIP",
          "Animal, owner and validity are required");
  }

  public AnimalOwnershipAssignment end(LocalDate until, long expected) {
    if (until == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_OWNERSHIP_END", "An end date is required");
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "Ownership assignment has changed");
    if (period.until() != null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "OWNERSHIP_ALREADY_ENDED",
          "Closed ownership history cannot be rewritten");
    return new AnimalOwnershipAssignment(
        id,
        animalId,
        ownerId,
        new EffectivePeriod(period.from(), until),
        sourceDocumentId,
        version + 1);
  }
}
