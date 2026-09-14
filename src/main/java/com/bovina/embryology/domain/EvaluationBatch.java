package com.bovina.embryology.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.util.*;

public record EvaluationBatch(UUID batchId, Source source, List<Item> items) {
  public EvaluationBatch {
    StableIds.requireVersion7(batchId);
    if (items == null
        || items.isEmpty()
        || items.size() > 200
        || items.stream().anyMatch(Objects::isNull)) invalid();
    items = List.copyOf(items);
    source = source == null ? new Source(DataProvenance.Origin.MANUAL, null, null) : source;
    var itemIds = new HashSet<UUID>();
    var ids = new HashSet<UUID>();
    var embryos = new HashSet<UUID>();
    for (var item : items)
      if (!itemIds.add(StableIds.requireVersion7(item.itemId()))
          || !ids.add(StableIds.requireVersion7(item.id()))
          || !embryos.add(StableIds.requireVersion7(item.embryoId()))) invalid();
  }

  public record Item(
      UUID itemId,
      UUID id,
      UUID embryoId,
      UUID schemeVersionId,
      UUID developmentStageCodeId,
      UUID qualityGradeCodeId,
      Instant evaluatedAt,
      UUID evaluatorProfessionalId,
      String notes,
      UUID supersedesEvaluationId) {
    public EmbryoEvaluation evaluation(DataProvenance p) {
      return new EmbryoEvaluation(
          id,
          embryoId,
          schemeVersionId,
          developmentStageCodeId,
          qualityGradeCodeId,
          evaluatedAt,
          evaluatorProfessionalId,
          notes,
          supersedesEvaluationId,
          p);
    }
  }

  public record Source(DataProvenance.Origin origin, UUID sourceDocumentId, UUID apiClientId) {
    public Source {
      if (origin == null
          || (origin != DataProvenance.Origin.MANUAL
              && origin != DataProvenance.Origin.IMPORT
              && origin != DataProvenance.Origin.API)
          || (origin != DataProvenance.Origin.MANUAL && sourceDocumentId == null)
          || ((origin == DataProvenance.Origin.API) != (apiClientId != null))) invalid();
    }

    public DataProvenance provenance(ExecutionContext c, Instant now, UUID batch) {
      return new DataProvenance(
          origin,
          sourceDocumentId,
          origin == DataProvenance.Origin.IMPORT ? batch : null,
          apiClientId,
          c.actorId(),
          now,
          null,
          null,
          null);
    }
  }

  public record Result(UUID batchId, List<ItemResult> items) {
    public Result {
      items = List.copyOf(items);
    }
  }

  public record ItemResult(UUID itemId, UUID evaluationId, String status) {}

  private static void invalid() {
    throw new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED,
        "INVALID_EVALUATION_BATCH",
        "Evaluation bulk requires distinct stable item, evaluation and embryo IDs");
  }
}
