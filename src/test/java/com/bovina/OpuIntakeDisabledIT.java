package com.bovina;

import static org.assertj.core.api.Assertions.*;

import com.bovina.opu.api.*;
import com.bovina.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.*;

@SpringBootTest
class OpuIntakeDisabledIT {
  @Autowired ApplicationContext context;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    TestDatabase.properties(r);
  }

  @Test
  void localBaselineDoesNotPublishConditionalIntakeEndpoints() {
    assertThat(context.getBeansOfType(TransportReceiptController.class)).isEmpty();
    assertThat(context.getBeansOfType(ExternalOocyteReceiptController.class)).isEmpty();
    assertThat(context.getBeansOfType(OpuController.class)).hasSize(1);
  }
}
