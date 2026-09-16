package com.bovina.platform.application;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StableIdsTest {
  @Test
  void generatesRfcVersion7AndPreservesClientIdentifiers() {
    var id = new StableIds().next();
    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    var clientId = UUID.fromString("01992678-9600-7000-8000-000000000001");
    assertThat(StableIds.requireVersion7(clientId)).isEqualTo(clientId);
    assertThatThrownBy(() -> StableIds.requireVersion7(UUID.randomUUID()))
        .isInstanceOf(ApplicationFailure.class);
  }
}
