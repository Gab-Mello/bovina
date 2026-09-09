package com.bovina.audit.application;

import com.bovina.platform.application.ExecutionContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(
    UUID id,
    ExecutionContext context,
    Instant occurredAt,
    String action,
    String entityType,
    UUID entityId,
    Long entityVersion,
    String reason,
    String previousState,
    String newState) {
  public AuditEvent {
    Objects.requireNonNull(id);
    Objects.requireNonNull(context);
    Objects.requireNonNull(occurredAt);
    Objects.requireNonNull(entityId);
    if (action == null
        || !action.matches("[A-Z_]{1,32}")
        || entityType == null
        || !entityType.matches("[A-Z_]{1,64}"))
      throw new IllegalArgumentException("Invalid audit action or target");
    if (reason != null && reason.length() > 500)
      throw new IllegalArgumentException("Audit reason too long");
    if ((action.equals("REVOKE") || action.equals("CORRECT"))
        && (reason == null || reason.isBlank()))
      throw new IllegalArgumentException("An audit reason is required");
  }
}
