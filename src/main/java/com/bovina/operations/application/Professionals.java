package com.bovina.operations.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.domain.*;
import com.bovina.operations.infrastructure.ProfessionalStore;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Professionals {
  private final TenantAccess access;
  private final ProfessionalStore professionals;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public Professionals(
      TenantAccess access,
      ProfessionalStore professionals,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.professionals = professionals;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public Professional register(ExecutionContext context, UUID key, Register input) {
    access.require(context, "master-data:write");
    var professional =
        new Professional(
            input.id(), input.name(), input.professionalType(), input.linkedUserId(), "ACTIVE", 0);
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_PROFESSIONAL_V1",
        input,
        Professional.class,
        () -> {
          if (input.linkedUserId() != null
              && !professionals.hasMembership(context.tenantId(), input.linkedUserId()))
            throw new ApplicationFailure(
                ApplicationFailure.Kind.NOT_FOUND, "MEMBERSHIP_NOT_FOUND", "Membership not found");
          professionals.insert(
              context.tenantId(), professional, context.actorId(), clock.instant());
          record(context, "CREATE", "PROFESSIONAL", professional.id(), 0, "ACTIVE");
          return professional;
        });
  }

  @Transactional(readOnly = true)
  public Professional get(ExecutionContext context, UUID id) {
    access.require(context, "master-data:read");
    return find(context, id);
  }

  @Transactional(readOnly = true)
  public PageResult<Professional> search(ExecutionContext context, SearchPage page) {
    access.require(context, "master-data:read");
    return new PageResult<>(
        professionals.search(context.tenantId(), page), page.page(), page.size());
  }

  @Transactional
  public Professional deactivate(ExecutionContext context, UUID key, UUID id, long expected) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "DEACTIVATE_PROFESSIONAL_V1",
        new Deactivate(id, expected),
        Professional.class,
        () -> {
          var p = find(context, id).deactivate(expected);
          professionals.deactivate(context.tenantId(), p);
          record(context, "DEACTIVATE", "PROFESSIONAL", id, p.version(), p.status());
          return p;
        });
  }

  @Transactional
  public ProfessionalCredential addCredential(
      ExecutionContext context, UUID key, UUID professional, Credential input) {
    access.require(context, "master-data:write");
    var credential =
        new ProfessionalCredential(
            input.id(),
            professional,
            input.issuer(),
            input.jurisdiction(),
            input.number(),
            input.period(),
            input.documentId());
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_CREDENTIAL_V1",
        credential,
        ProfessionalCredential.class,
        () -> {
          professionals
              .lock(context.tenantId(), professional)
              .orElseThrow(Professionals::missing)
              .requireActive();
          if (input.documentId() != null)
            documents.requireReference(context.tenantId(), input.documentId());
          professionals.addCredential(
              context.tenantId(), credential, context.actorId(), clock.instant());
          record(context, "REGISTER", "PROFESSIONAL_CREDENTIAL", credential.id(), 0, "RECORDED");
          return credential;
        });
  }

  @Transactional(readOnly = true)
  public PageResult<ProfessionalCredential> credentials(
      ExecutionContext context, UUID professional, SearchPage page) {
    access.require(context, "master-data:read");
    find(context, professional);
    return new PageResult<>(
        professionals.credentials(context.tenantId(), professional, page),
        page.page(),
        page.size());
  }

  private Professional find(ExecutionContext c, UUID id) {
    return professionals.find(c.tenantId(), id).orElseThrow(Professionals::missing);
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
  }

  private void record(
      ExecutionContext c, String action, String type, UUID id, long version, String status) {
    audit.record(
        new AuditEvent(
            ids.next(), c, clock.instant(), action, type, id, version, null, null, status));
  }

  public record Register(
      UUID id, String name, Professional.Type professionalType, UUID linkedUserId) {}

  public record Credential(
      UUID id,
      String issuer,
      String jurisdiction,
      String number,
      EffectivePeriod period,
      UUID documentId) {}

  private record Deactivate(UUID id, long expectedVersion) {}
}
