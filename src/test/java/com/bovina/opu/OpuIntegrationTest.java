package com.bovina.opu;

import com.bovina.support.integration.AuthenticatedIntegrationTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

abstract class OpuIntegrationTest extends AuthenticatedIntegrationTest {
  @DynamicPropertySource
  static void opuProperties(DynamicPropertyRegistry registry) {
    registry.add("bovina.opu.intake.transport-enabled", () -> true);
    registry.add("bovina.opu.intake.external-receipt-enabled", () -> true);
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> true);
  }
}
