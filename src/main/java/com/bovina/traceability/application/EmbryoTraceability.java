package com.bovina.traceability.application;

import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import com.bovina.traceability.infrastructure.TraceabilityQueries;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbryoTraceability {
  private final TenantAccess access;
  private final TraceabilityQueries queries;

  public EmbryoTraceability(TenantAccess access, TraceabilityQueries queries) {
    this.access = access;
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public Lineage lineage(ExecutionContext context, UUID embryo) {
    requireAccess(context);
    var lineage = queries.lineage(context.tenantId(), embryo);
    if (lineage == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND, "EMBRYO_NOT_FOUND", "Embryo not found");
    return lineage;
  }

  @Transactional(readOnly = true)
  public PageResult<TimelineEntry> timeline(
      ExecutionContext context, UUID embryo, SearchPage page) {
    lineage(context, embryo);
    return new PageResult<>(
        queries.timeline(context.tenantId(), embryo, page), page.page(), page.size());
  }

  private void requireAccess(ExecutionContext context) {
    for (var permission :
        new String[] {
          "embryology:read",
          "opu:read",
          "fertilization:read",
          "semen:read",
          "inventory:read",
          "transfer:read",
          "shipment:read"
        }) access.require(context, permission);
  }

  public record Lineage(
      UUID embryoId,
      String humanCode,
      UUID matingId,
      UUID collectionId,
      UUID donorId,
      UUID opuSessionId,
      UUID farmPropertyId,
      UUID semenBatchId,
      UUID sireId,
      UUID producerEstablishmentId,
      UUID currentPackageId,
      UUID currentLocationId,
      UUID transferId,
      UUID recipientCycleId,
      UUID recipientAnimalId) {}

  public record TimelineEntry(
      UUID factId, String type, Instant occurredAt, Instant recordedAt, UUID relatedId) {}
}
