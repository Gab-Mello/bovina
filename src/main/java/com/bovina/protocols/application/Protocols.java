package com.bovina.protocols.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import com.bovina.protocols.domain.*;
import com.bovina.protocols.infrastructure.ProtocolStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Protocols {
  private final TenantAccess access;
  private final ProtocolStore protocols;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public Protocols(
      TenantAccess access,
      ProtocolStore protocols,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.protocols = protocols;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public ProtocolDefinition register(ExecutionContext c, UUID key, Register input) {
    access.require(c, "protocol:manage");
    var definition =
        new ProtocolDefinition(
            input.id(), input.purpose(), input.name(), input.reference(), "ACTIVE", 0);
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_PROTOCOL_V1",
        input,
        ProtocolDefinition.class,
        () -> {
          protocols.insertDefinition(c.tenantId(), definition, c.actorId(), clock.instant());
          record(c, "REGISTER", "PROTOCOL_DEFINITION", definition.id(), 0L, "ACTIVE");
          return definition;
        });
  }

  @Transactional
  public ProtocolDefinition deactivate(ExecutionContext c, UUID key, UUID id, long expected) {
    access.require(c, "protocol:manage");
    return receipts.replayOrExecute(
        c,
        key,
        "DEACTIVATE_PROTOCOL_V1",
        new Deactivate(id, expected),
        ProtocolDefinition.class,
        () -> {
          var d = find(c, id).deactivate(expected);
          protocols.deactivate(c.tenantId(), d);
          record(c, "DEACTIVATE", "PROTOCOL_DEFINITION", id, d.version(), d.status());
          return d;
        });
  }

  @Transactional
  public ProtocolVersion publish(ExecutionContext c, UUID key, UUID definition, Publish input) {
    access.require(c, "protocol:manage");
    return receipts.replayOrExecute(
        c,
        key,
        "PUBLISH_PROTOCOL_VERSION_V1",
        new Publication(definition, input),
        ProtocolVersion.class,
        () -> {
          protocols
              .lock(c.tenantId(), definition)
              .orElseThrow(() -> missing("PROTOCOL"))
              .requireActive();
          var v =
              new ProtocolVersion(
                  input.id(),
                  definition,
                  input.revision(),
                  input.effectivePeriod(),
                  input.contentReference(),
                  input.checksum(),
                  input.documentId(),
                  c.actorId(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS));
          if (input.documentId() != null)
            documents.requireReference(c.tenantId(), input.documentId());
          protocols.publish(c.tenantId(), v);
          record(c, "PUBLISH", "PROTOCOL_VERSION", v.id(), null, "PUBLISHED");
          return v;
        });
  }

  @Transactional(readOnly = true)
  public ProtocolDefinition get(ExecutionContext c, UUID id) {
    access.require(c, "master-data:read");
    return find(c, id);
  }

  @Transactional(readOnly = true)
  public PageResult<ProtocolDefinition> search(ExecutionContext c, SearchPage page) {
    access.require(c, "master-data:read");
    return new PageResult<>(protocols.search(c.tenantId(), page), page.page(), page.size());
  }

  @Transactional(readOnly = true)
  public ProtocolVersion version(ExecutionContext c, UUID definition, UUID id) {
    access.require(c, "master-data:read");
    var v = protocols.version(c.tenantId(), id).orElseThrow(() -> missing("PROTOCOL_VERSION"));
    if (!v.definitionId().equals(definition)) throw missing("PROTOCOL_VERSION");
    return v;
  }

  @Transactional(readOnly = true)
  public PageResult<ProtocolVersion> versions(
      ExecutionContext c, UUID definition, SearchPage page) {
    access.require(c, "master-data:read");
    find(c, definition);
    return new PageResult<>(
        protocols.versions(c.tenantId(), definition, page), page.page(), page.size());
  }

  @Transactional
  public AppliedVersion requireApplicable(
      ExecutionContext c, UUID versionId, String purpose, LocalDate on) {
    access.require(c, "master-data:read");
    var v =
        protocols.version(c.tenantId(), versionId).orElseThrow(() -> missing("PROTOCOL_VERSION"));
    var d = protocols.lock(c.tenantId(), v.definitionId()).orElseThrow(() -> missing("PROTOCOL"));
    if (protocols.withdrawal(c.tenantId(), versionId).isPresent())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "PROTOCOL_VERSION_WITHDRAWN",
          "Protocol version is withdrawn from new use");
    d.requireActive();
    v.requireApplicable(d.purpose(), purpose, on);
    return new AppliedVersion(v.id(), v.definitionId(), d.purpose(), v.revision(), v.checksum());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public ProtocolVersion requireAvailableVersion(UUID tenant, UUID versionId) {
    var version =
        protocols.version(tenant, versionId).orElseThrow(() -> missing("PROTOCOL_VERSION"));
    if (protocols.withdrawal(tenant, versionId).isPresent())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "PROTOCOL_VERSION_WITHDRAWN",
          "Protocol version is withdrawn from new use");
    return version;
  }

  private ProtocolDefinition find(ExecutionContext c, UUID id) {
    return protocols.find(c.tenantId(), id).orElseThrow(() -> missing("PROTOCOL"));
  }

  @Transactional
  public ProtocolWithdrawal withdraw(
      ExecutionContext c, UUID key, UUID definition, UUID id, String reason) {
    access.require(c, "protocol:manage");
    return receipts.replayOrExecute(
        c,
        key,
        "WITHDRAW_PROTOCOL_VERSION_V1",
        new WithdrawalIntent(definition, id, reason),
        ProtocolWithdrawal.class,
        () -> {
          protocols.lock(c.tenantId(), definition).orElseThrow(() -> missing("PROTOCOL"));
          var v =
              protocols.version(c.tenantId(), id).orElseThrow(() -> missing("PROTOCOL_VERSION"));
          if (!v.definitionId().equals(definition)) throw missing("PROTOCOL_VERSION");
          var withdrawal =
              new ProtocolWithdrawal(
                  id, reason, c.actorId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
          protocols.withdraw(c.tenantId(), withdrawal);
          record(c, "DEACTIVATE", "PROTOCOL_VERSION", id, null, "WITHDRAWN");
          return withdrawal;
        });
  }

  @Transactional(readOnly = true)
  public ProtocolWithdrawal withdrawal(ExecutionContext c, UUID definition, UUID id) {
    version(c, definition, id);
    return protocols.withdrawal(c.tenantId(), id).orElseThrow(() -> missing("PROTOCOL_WITHDRAWAL"));
  }

  private record WithdrawalIntent(UUID definition, UUID id, String reason) {}

  private static ApplicationFailure missing(String type) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, type + "_NOT_FOUND", "Protocol record not found");
  }

  private void record(
      ExecutionContext c, String action, String type, UUID id, Long version, String status) {
    audit.record(
        new AuditEvent(
            ids.next(), c, clock.instant(), action, type, id, version, null, null, status));
  }

  public record Register(UUID id, String purpose, String name, String reference) {}

  public record Publish(
      UUID id,
      String revision,
      EffectivePeriod effectivePeriod,
      String contentReference,
      String checksum,
      UUID documentId) {}

  public record AppliedVersion(
      UUID id, UUID definitionId, String purpose, String revision, String checksum) {}

  private record Deactivate(UUID id, long expectedVersion) {}

  private record Publication(UUID definition, Publish input) {}
}
