package com.bovina.opu.application;

import com.bovina.opu.domain.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.util.*;

public record CollectionBatch(
    UUID batchId, long expectedSessionVersion, Source source, List<Item> items) {
  public CollectionBatch {
    StableIds.requireVersion7(batchId);
    if (expectedSessionVersion < 0
        || items == null
        || items.isEmpty()
        || items.size() > 100
        || items.stream().anyMatch(Objects::isNull))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_COLLECTION_BATCH",
          "Supply between one and 100 collection items and a session version");
    items = List.copyOf(items);
    source = source == null ? new Source(DataProvenance.Origin.MANUAL, null, null) : source;
    var ids = new HashSet<UUID>();
    for (var item : items) {
      StableIds.requireVersion7(item.itemId());
      if (!ids.add(item.itemId()))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED, "DUPLICATE_BATCH_ITEM", "Item IDs must be distinct");
    }
  }

  public record Item(
      UUID itemId,
      UUID id,
      UUID donorId,
      Instant collectedAt,
      Integer totalRecovered,
      Integer viable,
      Integer folliclesAspirated,
      String notes) {
    public OocyteCollection.Registration registration() {
      if (totalRecovered == null || viable == null)
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_OOCYTE_COUNTS",
            "Total and viable counts are required");
      return new OocyteCollection.Registration(
          id,
          donorId,
          collectedAt,
          new OocyteCounts(totalRecovered, viable, folliclesAspirated),
          notes);
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
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_OPU_PROVENANCE",
            "Import/API sources require document evidence; API also requires its source client reference");
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

  public record ItemResult(UUID itemId, UUID collectionId, String status, String errorCode) {}

  public record Result(UUID batchId, boolean dryRun, List<ItemResult> items) {
    public Result {
      items = List.copyOf(items);
    }
  }
}
