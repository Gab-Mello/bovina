package com.bovina.identity.application;

public record AuthenticatedIdentity(String issuer, String subject) {
  public AuthenticatedIdentity {
    if (issuer == null
        || issuer.isBlank()
        || issuer.length() > 512
        || subject == null
        || subject.isBlank()
        || subject.length() > 255)
      throw new IllegalArgumentException("Invalid authenticated identity");
  }
}
