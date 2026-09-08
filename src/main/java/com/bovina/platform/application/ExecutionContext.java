package com.bovina.platform.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted application input, not an HTTP request DTO. Identity/membership resolution belongs to the
 * authentication adapter introduced in Phase 1.
 */
public record ExecutionContext(
    UUID tenantId, UUID actorId, Set<String> permissions, UUID correlationId) {
  public ExecutionContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(correlationId, "correlationId");
    permissions = Set.copyOf(permissions);
    if (permissions.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("Permissions must not be blank");
    }
  }
}
