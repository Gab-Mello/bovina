package com.bovina.parties.domain;

import com.bovina.platform.application.*;
import java.util.UUID;

/** Identifier uniqueness is scoped to this counterparty; no universal tax-ID merge is inferred. */
public record CounterpartyIdentifier(UUID id, String type, String issuer, String value) {
  public CounterpartyIdentifier {
    StableIds.requireVersion7(id);
    if (type == null
        || !type.matches("[A-Z][A-Z0-9_]{0,39}")
        || value == null
        || value.isBlank()
        || value.length() > 160
        || (issuer != null && issuer.length() > 120))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_COUNTERPARTY_IDENTIFIER",
          "Identifier type or value is invalid");
    value = value.strip();
    issuer = issuer == null || issuer.isBlank() ? null : issuer.strip();
  }

  // Keep case significant until the identifier scheme provides a stronger normalization contract.
  public String normalizedValue() {
    return value;
  }
}
