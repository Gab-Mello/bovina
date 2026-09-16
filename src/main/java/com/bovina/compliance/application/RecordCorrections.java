package com.bovina.compliance.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.compliance.infrastructure.CorrectionStore;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import com.bovina.platform.application.StableIds;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordCorrections {
  private static final Set<String> SUBJECTS =
      Set.of(
          "MATING", "CRYOPRESERVATION_ITEM", "PACKAGE_ITEM", "EMBRYO_TRANSFER", "PREGNANCY_CHECK");
  private final CorrectionStore store;
  private final TenantAccess access;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public RecordCorrections(
      CorrectionStore store,
      TenantAccess access,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.store = store;
    this.access = access;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public View request(ExecutionContext c, UUID key, Request input) {
    access.require(c, "lineage:correct");
    if (!key.equals(input.id())) throw rejected("CORRECTION_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "REQUEST_RECORD_CORRECTION_V1",
        input,
        View.class,
        () -> {
          var subject = store.subject(c.tenantId(), input.subjectType(), input.subjectId());
          if (subject == null) throw missing();
          if (input.expectedSubjectVersion() != null
              && !input.expectedSubjectVersion().equals(subject.version()))
            throw conflict("CORRECTION_SUBJECT_VERSION_CHANGED");
          if (input.sourceDocumentId() != null)
            documents.requireReference(c.tenantId(), input.sourceDocumentId());
          if (!input.subjectType().equals("MATING")
              && (input.proposal().semenBatchId() != null
                  || input.proposal().fertilizedAt() != null))
            throw rejected("INVALID_CORRECTION_PROPOSAL");
          if (input.proposal().semenBatchId() != null) {
            // Resolve a declared replacement through its tenant-owned relationship, never through
            // an arbitrary UUID.
            store.requireSemenBatch(c.tenantId(), input.proposal().semenBatchId());
          }
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.insert(c, input, subject, now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "CORRECT",
                  input.subjectType(),
                  input.subjectId(),
                  subject.version(),
                  input.reason(),
                  null,
                  "REQUESTED"));
          return store.find(c.tenantId(), input.id(), false);
        });
  }

  @Transactional
  public View reject(ExecutionContext c, UUID key, UUID id, Rejection input) {
    access.require(c, "lineage:correct");
    return receipts.replayOrExecute(
        c,
        key,
        "REJECT_RECORD_CORRECTION_V1",
        new RejectionIntent(id, input),
        View.class,
        () -> {
          var correction = find(c, id, true);
          if (!correction.status().equals("REQUESTED"))
            throw conflict("CORRECTION_ALREADY_REVIEWED");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          store.reject(c, key, id, input.reason(), now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "REJECT",
                  "RECORD_CORRECTION",
                  id,
                  null,
                  input.reason(),
                  "REQUESTED",
                  "REJECTED"));
          return store.find(c.tenantId(), id, false);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "compliance:read");
    return find(c, id, false);
  }

  @Transactional(readOnly = true)
  public PageResult<View> page(ExecutionContext c, SearchPage page) {
    access.require(c, "compliance:read");
    return new PageResult<>(store.page(c.tenantId(), page), page.page(), page.size());
  }

  @Transactional(readOnly = true)
  public Impact impact(ExecutionContext c, UUID id) {
    access.require(c, "compliance:read");
    var correction = find(c, id, false);
    return store.impact(c.tenantId(), correction.subjectType(), correction.subjectId());
  }

  private View find(ExecutionContext c, UUID id, boolean lock) {
    var found = store.find(c.tenantId(), id, lock);
    if (found == null) throw missing();
    return found;
  }

  public record Request(
      UUID id,
      String subjectType,
      UUID subjectId,
      Long expectedSubjectVersion,
      String reason,
      UUID sourceDocumentId,
      Proposal proposal) {
    public Request {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(subjectId);
      if (subjectType == null
          || !SUBJECTS.contains(subjectType)
          || proposal == null
          || expectedSubjectVersion != null && expectedSubjectVersion < 0)
        throw rejected("INVALID_CORRECTION_REQUEST");
      reason = RecordCorrections.reason(reason);
    }
  }

  /**
   * A proposal is not an effective replacement; applying it awaits the validated remediation
   * policy.
   */
  public record Proposal(UUID semenBatchId, Instant fertilizedAt, String semantics) {
    public Proposal {
      if (semantics == null || semantics.isBlank() || semantics.length() > 2000)
        throw rejected("CORRECTION_SEMANTICS_REQUIRED");
      if (semenBatchId != null) StableIds.requireVersion7(semenBatchId);
      semantics = semantics.strip();
    }
  }

  public record Rejection(String reason) {
    public Rejection {
      reason = RecordCorrections.reason(reason);
    }
  }

  public record View(
      UUID id,
      String subjectType,
      UUID subjectId,
      String reason,
      String previousSemantics,
      String proposedSemantics,
      Long subjectVersion,
      String status,
      Instant requestedAt) {}

  public record Impact(
      long affectedEmbryos,
      long activePackages,
      long performedTransfers,
      String applicationBlocker) {}

  private record RejectionIntent(UUID id, Rejection rejection) {}

  private static String reason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw rejected("CORRECTION_REASON_REQUIRED");
    return reason.strip();
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND,
        "CORRECTION_SUBJECT_NOT_FOUND",
        "Correction or subject not found");
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Correction request is invalid");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Correction conflicts with current history");
  }
}
