package com.bovina.operations.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.util.UUID;

public record ProfessionalCredential(
    UUID id,
    UUID professionalId,
    String issuer,
    String jurisdiction,
    String number,
    EffectivePeriod period,
    UUID documentId) {
  public ProfessionalCredential {
    StableIds.requireVersion7(id);
    if (professionalId == null
        || issuer == null
        || issuer.isBlank()
        || issuer.length() > 120
        || number == null
        || number.isBlank()
        || number.length() > 120
        || period == null
        || (jurisdiction != null && jurisdiction.length() > 80))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_CREDENTIAL",
          "Credential issuer, number and validity are required");
    issuer = issuer.strip();
    number = number.strip();
  }
}
