package com.bovina.identity.infrastructure;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("bovina.bootstrap")
public record BootstrapProperties(boolean enabled, String issuer, String subject) {
  @AssertTrue(message = "Enabled bootstrap requires an explicit trusted issuer and subject")
  public boolean isIdentityConfigured() {
    return !enabled
        || (issuer != null && !issuer.isBlank() && subject != null && !subject.isBlank());
  }

  public boolean allows(String issuer, String subject) {
    return enabled && this.issuer.equals(issuer) && this.subject.equals(subject);
  }
}
