package com.bovina.identity.application;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantAccessTest {
  @Test
  void applicationBoundaryRejectsMissingPermissionBeforePersistence() {
    var access = new TenantAccess(null, Clock.systemUTC());
    var context =
        new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), Set.of("client:read"), UUID.randomUUID());
    assertThatThrownBy(() -> access.require(context, "client:create"))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
  }
}
