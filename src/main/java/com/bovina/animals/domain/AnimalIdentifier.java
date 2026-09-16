package com.bovina.animals.domain;

import com.bovina.platform.application.*;
import java.time.LocalDate;
import java.util.UUID;

public record AnimalIdentifier(
    UUID id,
    UUID animalId,
    Type type,
    IdentifierValue identifier,
    LocalDate validFrom,
    LocalDate validUntil,
    Status status,
    long version) {
  public AnimalIdentifier {
    StableIds.requireVersion7(id);
    if (animalId == null
        || type == null
        || identifier == null
        || status == null
        || (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_ANIMAL_IDENTIFIER",
          "Invalid identifier or validity interval");
  }

  public AnimalIdentifier retire(Status disposition, long expected, String reason) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Identifier has changed");
    if (status != Status.ACTIVE)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "IDENTIFIER_ALREADY_RETIRED",
          "Retired identifiers cannot be rewritten");
    if (disposition == null
        || disposition == Status.ACTIVE
        || reason == null
        || reason.isBlank()
        || reason.length() > 500)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_IDENTIFIER_RETIREMENT",
          "Retirement requires a disposition and reason");
    return new AnimalIdentifier(
        id, animalId, type, identifier, validFrom, validUntil, disposition, version + 1);
  }

  public enum Type {
    RGD,
    CGD,
    CEIP,
    CEGDF,
    RGN,
    EAR_TAG,
    EID,
    OTHER
  }

  public enum Status {
    ACTIVE,
    CORRECTED,
    REVOKED
  }
}
