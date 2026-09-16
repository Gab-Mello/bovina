package com.bovina.embryology.application;

import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.embryology.domain.*;
import com.bovina.embryology.domain.AssessmentCatalog.Dimension;
import com.bovina.embryology.infrastructure.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import com.bovina.platform.infrastructure.CommandReceiptStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbryoEvaluations {
  private final TenantAccess access;
  private final EmbryoRepository embryos;
  private final EmbryologyFacts facts;
  private final AssessmentSchemes schemes;
  private final OpuFacilities facilities;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final CommandReceiptStore hashes;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public EmbryoEvaluations(
      TenantAccess access,
      EmbryoRepository embryos,
      EmbryologyFacts facts,
      AssessmentSchemes schemes,
      OpuFacilities facilities,
      DocumentReferences documents,
      CommandReceipts receipts,
      CommandReceiptStore hashes,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.embryos = embryos;
    this.facts = facts;
    this.schemes = schemes;
    this.facilities = facilities;
    this.documents = documents;
    this.receipts = receipts;
    this.hashes = hashes;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public EvaluationBatch.Result record(ExecutionContext c, UUID key, EvaluationBatch batch) {
    access.require(c, "embryology:write");
    if (!batch.batchId().equals(key))
      throw rejected("BATCH_KEY_MISMATCH", "Idempotency key must equal batch ID");
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_EMBRYO_EVALUATIONS_V1",
        batch,
        EvaluationBatch.Result.class,
        () -> recordOnce(c, batch));
  }

  private EvaluationBatch.Result recordOnce(ExecutionContext c, EvaluationBatch batch) {
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (batch.source().sourceDocumentId() != null)
      documents.requireReference(c.tenantId(), batch.source().sourceDocumentId());
    if (batch.source().origin() == DataProvenance.Origin.IMPORT)
      facts.registerImport(
          c,
          batch.batchId(),
          "EMBRYO_EVALUATIONS",
          batch.source().sourceDocumentId(),
          hashes.hash(c.actorId(), "EMBRYO_EVALUATION_IMPORT_V1", batch),
          now);
    var locked = new HashMap<UUID, Embryo>();
    batch.items().stream()
        .map(EvaluationBatch.Item::embryoId)
        .sorted()
        .forEach(
            id ->
                locked.put(
                    id,
                    embryos.lock(c.tenantId(), id).orElseThrow(EmbryoEvaluations::missingEmbryo)));
    batch.items().stream()
        .map(EvaluationBatch.Item::evaluatorProfessionalId)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(id -> facilities.requireProfessional(c.tenantId(), id));
    var versions = new HashMap<UUID, AssessmentCatalog.Version>();
    batch.items().stream()
        .map(EvaluationBatch.Item::schemeVersionId)
        .distinct()
        .sorted()
        .forEach(id -> versions.put(id, schemes.requirePublished(c.tenantId(), id)));
    var evaluations = new ArrayList<EmbryoEvaluation>();
    for (var item : batch.items()) {
      var embryo = locked.get(item.embryoId());
      if (item.evaluatedAt() != null && item.evaluatedAt().isBefore(embryo.identifiedAt()))
        throw rejected(
            "EVALUATION_PRECEDES_IDENTIFICATION",
            "Evaluation cannot precede embryo identification");
      var codes =
          schemes.requireCodes(
              c.tenantId(),
              item.schemeVersionId(),
              List.of(item.developmentStageCodeId(), item.qualityGradeCodeId()));
      if (codes.get(item.developmentStageCodeId()).dimension() != Dimension.DEVELOPMENT_STAGE
          || codes.get(item.qualityGradeCodeId()).dimension() != Dimension.QUALITY_GRADE)
        throw rejected(
            "ASSESSMENT_CODE_DIMENSION_MISMATCH",
            "Stage and quality codes must have the matching dimensions");
      if (item.supersedesEvaluationId() != null) {
        var previous = facts.evaluation(c.tenantId(), item.supersedesEvaluationId());
        if (previous == null || !previous.embryoId().equals(item.embryoId()))
          throw rejected(
              "INVALID_EVALUATION_SUPERSESSION",
              "Superseded evaluation must belong to the same embryo");
      }
      evaluations.add(item.evaluation(batch.source().provenance(c, now, batch.batchId())));
    }
    facts.insertEvaluations(c.tenantId(), evaluations);
    audit.recordAll(
        evaluations.stream()
            .map(
                e ->
                    new AuditEvent(
                        ids.next(),
                        c,
                        now,
                        "EVALUATE",
                        "EMBRYO",
                        e.embryoId(),
                        null,
                        null,
                        null,
                        e.id().toString()))
            .toList());
    return new EvaluationBatch.Result(
        batch.batchId(),
        batch.items().stream()
            .map(i -> new EvaluationBatch.ItemResult(i.itemId(), i.id(), "APPLIED"))
            .toList());
  }

  @Transactional(readOnly = true)
  public List<EmbryoEvaluation> history(ExecutionContext c, UUID embryo) {
    access.require(c, "embryology:read");
    if (embryos.findByOrganizationIdAndId(c.tenantId(), embryo).isEmpty()) throw missingEmbryo();
    return facts.history(c.tenantId(), embryo);
  }

  private static ApplicationFailure missingEmbryo() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "EMBRYO_NOT_FOUND", "Embryo not found");
  }

  private static ApplicationFailure rejected(String code, String detail) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, detail);
  }
}
