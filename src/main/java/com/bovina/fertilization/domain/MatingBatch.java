package com.bovina.fertilization.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.util.*;

public record MatingBatch(UUID batchId, Source source, List<Item> items) {
  public MatingBatch {
    StableIds.requireVersion7(batchId);
    if (items == null
        || items.isEmpty()
        || items.size() > 100
        || items.stream().anyMatch(Objects::isNull))
      invalid("Supply between one and 100 mating items");
    items = List.copyOf(items);
    source = source == null ? new Source(DataProvenance.Origin.MANUAL, null, null) : source;
    var itemIds = new HashSet<UUID>();
    var matingIds = new HashSet<UUID>();
    for (var item : items) {
      StableIds.requireVersion7(item.itemId());
      StableIds.requireVersion7(item.id());
      if (!itemIds.add(item.itemId()) || !matingIds.add(item.id()))
        invalid("Batch item and mating IDs must be distinct");
    }
  }

  public record Item(
      UUID itemId,
      UUID id,
      UUID collectionId,
      UUID semenBatchId,
      Integer allocatedOocytes,
      Instant fertilizedAt,
      String method,
      UUID responsibleProfessionalId) {
    public Mating mating(DataProvenance provenance) {
      if (allocatedOocytes == null) invalid("Allocated oocyte count is required");
      return new Mating(
          id,
          collectionId,
          semenBatchId,
          allocatedOocytes,
          fertilizedAt,
          method,
          responsibleProfessionalId,
          Mating.Status.FERTILIZED,
          0,
          provenance);
    }
  }

  public record Source(DataProvenance.Origin origin, UUID sourceDocumentId, UUID apiClientId) {
    public Source {
      if (origin == null
          || (origin != DataProvenance.Origin.MANUAL
              && origin != DataProvenance.Origin.IMPORT
              && origin != DataProvenance.Origin.API)
          || (origin != DataProvenance.Origin.MANUAL && sourceDocumentId == null)
          || ((origin == DataProvenance.Origin.API) != (apiClientId != null)))
        invalid("Import/API sources require document evidence");
    }

    public DataProvenance provenance(ExecutionContext context, Instant now, UUID batchId) {
      return new DataProvenance(
          origin,
          sourceDocumentId,
          origin == DataProvenance.Origin.IMPORT ? batchId : null,
          apiClientId,
          context.actorId(),
          now,
          null,
          null,
          null);
    }
  }

  public record ItemResult(UUID itemId, UUID matingId, String status) {}

  public record Result(UUID batchId, List<ItemResult> items) {
    public Result {
      items = List.copyOf(items);
    }
  }

  private static void invalid(String detail) {
    throw new ApplicationFailure(ApplicationFailure.Kind.REJECTED, "INVALID_MATING_BATCH", detail);
  }
}
