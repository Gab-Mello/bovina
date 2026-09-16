package com.bovina.animals.domain;

import com.bovina.platform.application.*;
import java.text.Normalizer;
import java.util.Locale;

/** Preserve punctuation and leading zeros; association-specific formats are not inferred. */
public record IdentifierValue(String value, String issuer) {
  public IdentifierValue {
    if (value == null || value.isBlank()) throw invalid();
    value = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (value.length() > 160 || value.toUpperCase(Locale.ROOT).length() > 160) throw invalid();
    issuer =
        issuer == null || issuer.isBlank()
            ? null
            : Normalizer.normalize(issuer.strip(), Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
    if (issuer != null && issuer.length() > 120) throw invalid();
  }

  public String normalized() {
    return value.toUpperCase(Locale.ROOT);
  }

  private static ApplicationFailure invalid() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED,
        "INVALID_ANIMAL_IDENTIFIER",
        "Identifier value or issuer is invalid");
  }
}
