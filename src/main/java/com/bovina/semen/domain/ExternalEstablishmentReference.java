package com.bovina.semen.domain;

import com.bovina.platform.application.*;
import java.util.*;

public record ExternalEstablishmentReference(
    UUID id,
    UUID legalPartyId,
    String name,
    String establishmentType,
    String registrationNumber,
    String registrationAuthority,
    String country,
    String verificationStatus,
    UUID verificationDocumentId,
    String status,
    long version) {
  public ExternalEstablishmentReference {
    StableIds.requireVersion7(id);
    name = require(name, 200, "name");
    establishmentType = code(establishmentType, "establishment type");
    country = require(country, 2, "country").toUpperCase(Locale.ROOT);
    if (country.length() != 2) invalid("country");
    verificationStatus = code(verificationStatus, "verification status");
    if (registrationNumber != null && registrationNumber.length() > 120)
      invalid("registration number");
    if (registrationAuthority != null && registrationAuthority.length() > 160)
      invalid("registration authority");
    if (status == null) status = "ACTIVE";
    if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) invalid("status");
  }

  public void requireActive() {
    if (!status.equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "EXTERNAL_ESTABLISHMENT_INACTIVE",
          "External establishment is inactive");
  }

  private static String code(String value, String field) {
    value = require(value, 48, field).toUpperCase(Locale.ROOT);
    if (!value.matches("[A-Z][A-Z0-9_]{0,47}")) invalid(field);
    return value;
  }

  private static String require(String value, int max, String field) {
    if (value == null || value.isBlank() || value.length() > max) invalid(field);
    return value.strip();
  }

  private static void invalid(String field) {
    throw new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, "INVALID_EXTERNAL_ESTABLISHMENT", "Invalid " + field);
  }
}
