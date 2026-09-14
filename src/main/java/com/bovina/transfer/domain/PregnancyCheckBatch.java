package com.bovina.transfer.domain;

import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.*;

public record PregnancyCheckBatch(UUID batchId, List<Item> items) {
  public PregnancyCheckBatch {
    StableIds.requireVersion7(batchId);
    if (items == null || items.isEmpty() || items.size() > 200)
      throw rejected("INVALID_PREGNANCY_CHECK_BATCH");
    items = List.copyOf(items);
    unique(items.stream().map(Item::itemId).toList(), "DUPLICATE_ITEM_ID");
    unique(items.stream().map(Item::id).toList(), "DUPLICATE_PREGNANCY_CHECK_ID");
    unique(
        items.stream().map(Item::supersedesCheckId).filter(Objects::nonNull).toList(),
        "DUPLICATE_SUPERSEDED_CHECK");
  }

  public record Item(
      UUID itemId,
      UUID id,
      UUID transferId,
      Instant checkedAt,
      String timezone,
      PregnancyCheck.Result result,
      String methodCode,
      String observations,
      UUID professionalId,
      UUID supersedesCheckId,
      String correctionReason) {
    public Item {
      StableIds.requireVersion7(itemId);
      StableIds.requireVersion7(id);
    }
  }

  public record ItemResult(UUID itemId, UUID checkId, UUID transferId, String status) {}

  public record Result(UUID batchId, List<ItemResult> items) {
    public Result {
      items = List.copyOf(items);
    }
  }

  private static void unique(List<UUID> ids, String code) {
    if (new HashSet<>(ids).size() != ids.size()) throw rejected(code);
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Pregnancy check batch is invalid");
  }
}
