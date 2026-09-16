package com.bovina.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SecurityPropertiesTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void missingIdentityConfigurationFailsStartup() {
    context.run(application -> assertThat(application).hasFailed());
  }

  @Test
  void validConfigurationBindsWithoutProviderNetworkAccess() {
    context
        .withPropertyValues(
            "bovina.security.issuer=https://identity.example",
            "bovina.security.jwk-set-uri=https://identity.example/jwks",
            "bovina.security.audience=bovina")
        .run(
            application ->
                assertThat(application).hasNotFailed().hasSingleBean(SecurityProperties.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/relative/jwks",
        "file:///tmp/jwks",
        "https://user:password@identity.example/jwks"
      })
  void invalidEndpointFailsStartup(String endpoint) {
    context
        .withPropertyValues(
            "bovina.security.issuer=https://identity.example",
            "bovina.security.jwk-set-uri=" + endpoint,
            "bovina.security.audience=bovina")
        .run(application -> assertThat(application).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SecurityProperties.class)
  static class PropertiesConfiguration {}
}
