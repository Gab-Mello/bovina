package com.bovina.platform.application;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationContextTest {
  @Test
  void contextCannotShareMutablePermissionsWithItsCaller() {
    var permissions = new HashSet<>(Set.of("client:read"));
    var context =
        new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), permissions, UUID.randomUUID());
    permissions.clear();
    assertThat(context.permissions()).containsExactly("client:read");
    assertThatThrownBy(() -> context.permissions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void contextRequiresIdentityAndRejectsBlankPermissions() {
    var id = UUID.randomUUID();
    assertThatThrownBy(() -> new ExecutionContext(null, id, Set.of(), id))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionContext(id, id, Set.of(" "), id))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void commandMetadataPreservesClientIdentityAndTimeWithoutGeneratingThemAgain() {
    var id = UUID.fromString("01992678-9600-7000-8000-000000000001");
    var occurred = Instant.parse("2026-09-08T10:00:00Z");
    assertThat(new CommandMetadata(id, occurred)).isEqualTo(new CommandMetadata(id, occurred));
    assertThatThrownBy(() -> new CommandMetadata(null, occurred))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CommandMetadata(id, null))
        .isInstanceOf(NullPointerException.class);
  }
}
