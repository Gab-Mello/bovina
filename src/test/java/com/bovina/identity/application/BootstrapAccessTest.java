package com.bovina.identity.application;

import static org.assertj.core.api.Assertions.*;

import com.bovina.identity.infrastructure.BootstrapProperties;
import com.bovina.platform.application.ApplicationFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BootstrapAccessTest {
  @Test
  void disabledBootstrapRejectsBeforeAnyPersistence() {
    var service =
        new OrganizationBootstrap(
            null, new BootstrapProperties(false, null, null), null, null, null);
    assertThatThrownBy(
            () ->
                service.create(
                    new AuthenticatedIdentity("https://identity.invalid", "admin"),
                    UUID.randomUUID(),
                    null))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("BOOTSTRAP_DISABLED");
  }

  @Test
  void bootstrapMatchesBothIssuerAndSubject() {
    var policy = new BootstrapProperties(true, "https://trusted.invalid", "operator");
    assertThat(policy.allows("https://other.invalid", "operator")).isFalse();
    assertThat(policy.allows("https://trusted.invalid", "other")).isFalse();
    assertThat(policy.allows("https://trusted.invalid", "operator")).isTrue();
    assertThat(new BootstrapProperties(true, null, null).isIdentityConfigured()).isFalse();
  }
}
