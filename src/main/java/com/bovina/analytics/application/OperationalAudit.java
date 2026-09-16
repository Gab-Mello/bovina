package com.bovina.analytics.application;

import com.bovina.analytics.infrastructure.AuditHistoryQueries;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalAudit {
  private final TenantAccess access;
  private final AuditHistoryQueries queries;

  public OperationalAudit(TenantAccess access, AuditHistoryQueries queries) {
    this.access = access;
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public PageResult<Entry> search(
      ExecutionContext context, String entityType, UUID entityId, SearchPage page) {
    access.require(context, "compliance:read");
    if (entityType != null && !entityType.matches("[A-Z_]{1,64}"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_AUDIT_FILTER", "Use a valid entity type");
    return new PageResult<>(
        queries.search(context.tenantId(), entityType, entityId, page), page.page(), page.size());
  }

  public record Entry(
      UUID id,
      Instant occurredAt,
      UUID actorId,
      String action,
      String entityType,
      UUID entityId,
      Long entityVersion,
      String reason,
      UUID correlationId) {}
}
