package com.bovina.platform.domain;

import com.bovina.platform.application.ApplicationFailure;
import jakarta.persistence.Embeddable;

@Embeddable
public record Address(
    String addressLine, String municipality, String state, String country, String postalCode) {
  public Address {
    if (addressLine == null
        || addressLine.isBlank()
        || addressLine.length() > 250
        || municipality == null
        || municipality.isBlank()
        || municipality.length() > 120
        || state == null
        || state.isBlank()
        || state.length() > 80
        || country == null
        || !country.matches("[A-Z]{2}")
        || (postalCode != null && postalCode.length() > 32))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_ADDRESS",
          "Address line, municipality, state and a two-letter country are required");
    addressLine = addressLine.strip();
    municipality = municipality.strip();
    state = state.strip();
  }
}
