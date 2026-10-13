package com.bovina.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ProductionSecurityGuardTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withInitializer(application -> application.getEnvironment().setActiveProfiles("prod"))
          .withUserConfiguration(ProductionConfiguration.class)
          .withPropertyValues(
              "bovina.security.issuer=https://identity.example",
              "bovina.security.jwk-set-uri=https://identity.example/jwks",
              "bovina.security.audience=bovina");

  @Test
  void productionAcceptsExternalIdentityWithoutLocalCorsOrBootstrap() {
    context.run(application -> assertThat(application).hasNotFailed());
  }

  @Test
  void productionRejectsLocalIssuerAndJwks() {
    context
        .withPropertyValues("bovina.security.issuer=http://localhost:8090/default")
        .run(application -> assertThat(application).hasFailed());
    context
        .withPropertyValues("bovina.security.jwk-set-uri=http://dev-auth:8080/default/jwks")
        .run(application -> assertThat(application).hasFailed());
  }

  @Test
  void productionRejectsLocalCors() {
    context
        .withPropertyValues("bovina.cors.allowed-origins=http://localhost:5173")
        .run(application -> assertThat(application).hasFailed());
  }

  @Test
  void productionRejectsCombinedDevelopmentProfile() {
    context
        .withInitializer(
            application -> application.getEnvironment().setActiveProfiles("prod", "dev"))
        .run(application -> assertThat(application).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class})
  @Import(ProductionSecurityGuard.class)
  static class ProductionConfiguration {}
}
