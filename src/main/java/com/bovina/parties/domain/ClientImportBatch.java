package com.bovina.parties.domain;

import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.*;

public record ClientImportBatch(UUID batchId, Mode mode, UUID sourceDocumentId, List<Row> items) {
  public ClientImportBatch {
    StableIds.requireVersion7(batchId);
    if (mode == null
        || items == null
        || items.isEmpty()
        || items.size() > 100
        || items.stream().anyMatch(Objects::isNull))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_IMPORT_BATCH",
          "Choose ATOMIC or PARTIAL and submit 1..100 items");
    items = List.copyOf(items);
    var itemIds = new HashSet<UUID>();
    for (var row : items) {
      StableIds.requireVersion7(row.itemId());
      if (!itemIds.add(row.itemId()))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "DUPLICATE_IMPORT_ITEM",
            "Import item IDs must be unique within the batch");
    }
  }

  public List<ItemResult> validate(Set<UUID> existingIds) {
    var counts = new HashMap<UUID, Integer>();
    for (var row : items) if (row.id() != null) counts.merge(row.id(), 1, Integer::sum);
    return items.stream()
        .map(
            row -> {
              String code = null;
              if (row.id() == null || row.id().version() != 7 || row.id().variant() != 2)
                code = "INVALID_CLIENT_ID";
              else if (row.type() == null
                  || row.occurredAt() == null
                  || row.displayName() == null
                  || row.displayName().isBlank()
                  || row.displayName().length() > 200) code = "INVALID_CLIENT_DETAILS";
              else if (counts.get(row.id()) > 1) code = "DUPLICATE_CLIENT_IN_BATCH";
              else if (existingIds.contains(row.id())) code = "CLIENT_ID_ALREADY_EXISTS";
              return new ItemResult(
                  row.itemId(),
                  code == null ? row.id() : null,
                  code == null ? ItemStatus.VALID : ItemStatus.REJECTED,
                  code);
            })
        .toList();
  }

  public enum Mode {
    ATOMIC,
    PARTIAL
  }

  public void requireMode(Mode required) {
    if (mode != required)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "IMPORT_MODE_MISMATCH",
          "Use the transaction boundary declared by this batch");
  }

  public enum ItemStatus {
    VALID,
    APPLIED,
    REJECTED,
    NOT_APPLIED
  }

  public record Row(
      UUID itemId, UUID id, ClientType type, String displayName, Instant occurredAt) {}

  public record ItemResult(UUID itemId, UUID clientId, ItemStatus status, String errorCode) {}

  public record Result(UUID batchId, Mode mode, boolean dryRun, List<ItemResult> items) {
    public Result {
      items = List.copyOf(items);
    }
  }
}
