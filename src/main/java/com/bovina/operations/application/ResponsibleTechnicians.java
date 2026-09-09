package com.bovina.operations.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.domain.*;
import com.bovina.operations.infrastructure.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResponsibleTechnicians {
  private final TenantAccess access;
  private final EstablishmentRepository establishments;
  private final ProfessionalStore professionals;
  private final TechnicianAssignmentStore assignments;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public ResponsibleTechnicians(
      TenantAccess access,
      EstablishmentRepository establishments,
      ProfessionalStore professionals,
      TechnicianAssignmentStore assignments,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.establishments = establishments;
    this.professionals = professionals;
    this.assignments = assignments;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public ResponsibleTechnicianAssignment assign(
      ExecutionContext context, UUID key, UUID establishment, Assign input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "ASSIGN_RESPONSIBLE_TECHNICIAN_V1",
        new Intent(establishment, input),
        ResponsibleTechnicianAssignment.class,
        () -> {
          var e = lock(context, establishment);
          e.requireActive();
          professionals
              .lock(context.tenantId(), input.professionalId())
              .orElseThrow(() -> missing("PROFESSIONAL"))
              .requireActive();
          var credential =
              professionals
                  .credential(context.tenantId(), input.professionalId(), input.credentialId())
                  .orElseThrow(() -> missing("CREDENTIAL"));
          var assignment =
              new ResponsibleTechnicianAssignment(
                  input.id(),
                  establishment,
                  input.professionalId(),
                  credential.id(),
                  credential.issuer(),
                  credential.jurisdiction(),
                  credential.number(),
                  input.documentId(),
                  input.period(),
                  0);
          if (assignments.overlaps(
              context.tenantId(), establishment, input.professionalId(), input.period()))
            throw new ApplicationFailure(
                ApplicationFailure.Kind.CONFLICT,
                "TECHNICIAN_ASSIGNMENT_OVERLAP",
                "This professional already has an assignment in that period");
          if (input.documentId() != null)
            documents.requireReference(context.tenantId(), input.documentId());
          assignments.insert(context.tenantId(), assignment, context.actorId(), clock.instant());
          record(context, assignment, "ASSIGN");
          return assignment;
        });
  }

  @Transactional
  public ResponsibleTechnicianAssignment end(
      ExecutionContext context, UUID key, UUID establishment, UUID id, End input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "END_TECHNICIAN_ASSIGNMENT_V1",
        new EndIntent(establishment, id, input),
        ResponsibleTechnicianAssignment.class,
        () -> {
          lock(context, establishment);
          var assignment =
              assignments
                  .find(context.tenantId(), establishment, id)
                  .orElseThrow(() -> missing("ASSIGNMENT"))
                  .end(input.until(), input.expectedVersion());
          assignments.end(context.tenantId(), assignment);
          record(context, assignment, "END_ASSIGNMENT");
          return assignment;
        });
  }

  @Transactional(readOnly = true)
  public PageResult<ResponsibleTechnicianAssignment> history(
      ExecutionContext context, UUID establishment, SearchPage page) {
    access.require(context, "master-data:read");
    establishments
        .findByOrganizationIdAndId(context.tenantId(), establishment)
        .orElseThrow(() -> missing("ESTABLISHMENT"));
    return new PageResult<>(
        assignments.history(context.tenantId(), establishment, page), page.page(), page.size());
  }

  private Establishment lock(ExecutionContext c, UUID id) {
    return establishments.lock(c.tenantId(), id).orElseThrow(() -> missing("ESTABLISHMENT"));
  }

  private static ApplicationFailure missing(String type) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, type + "_NOT_FOUND", "Record not found");
  }

  private void record(ExecutionContext c, ResponsibleTechnicianAssignment a, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "TECHNICIAN_ASSIGNMENT",
            a.id(),
            a.version(),
            null,
            null,
            a.period().until() == null ? "OPEN" : "BOUNDED"));
  }

  public record Assign(
      UUID id, UUID professionalId, UUID credentialId, UUID documentId, EffectivePeriod period) {
    public Assign {
      StableIds.requireVersion7(id);
      if (professionalId == null || credentialId == null || period == null)
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_ASSIGNMENT",
            "Professional, credential and period are required");
    }
  }

  public record End(long expectedVersion, LocalDate until) {
    public End {
      if (expectedVersion < 0 || until == null)
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_ASSIGNMENT_END",
            "Expected version and end date are required");
    }
  }

  private record Intent(UUID establishment, Assign input) {}

  private record EndIntent(UUID establishment, UUID id, End input) {}
}
