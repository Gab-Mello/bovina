package com.bovina.transfer.domain;

import com.bovina.platform.application.*;
import java.time.*;
import java.util.*;

public final class TransferBatches {
  private TransferBatches() {}

  public record Reservation(UUID batchId, List<ReservationItem> items) {
    public Reservation {
      validateBatch(batchId, items);
      items = List.copyOf(items);
      unique(items.stream().map(ReservationItem::itemId).toList(), "DUPLICATE_ITEM_ID");
      unique(
          items.stream().map(ReservationItem::reservationId).toList(), "DUPLICATE_RESERVATION_ID");
      unique(items.stream().map(ReservationItem::embryoId).toList(), "DUPLICATE_EMBRYO");
    }
  }

  public record ReservationItem(
      UUID itemId,
      UUID reservationId,
      UUID embryoId,
      UUID recipientCycleId,
      long expectedEmbryoVersion) {
    public ReservationItem {
      StableIds.requireVersion7(itemId);
      StableIds.requireVersion7(reservationId);
      StableIds.requireVersion7(embryoId);
      StableIds.requireVersion7(recipientCycleId);
      if (expectedEmbryoVersion < 0) throw rejected("INVALID_EMBRYO_VERSION");
    }
  }

  public record Performance(UUID batchId, List<PerformanceItem> items) {
    public Performance {
      validateBatch(batchId, items);
      items = List.copyOf(items);
      unique(items.stream().map(PerformanceItem::itemId).toList(), "DUPLICATE_ITEM_ID");
      unique(items.stream().map(PerformanceItem::transferId).toList(), "DUPLICATE_TRANSFER_ID");
      unique(
          items.stream().map(PerformanceItem::reservationId).toList(), "DUPLICATE_RESERVATION_ID");
    }
  }

  public record PerformanceItem(
      UUID itemId,
      UUID transferId,
      UUID reservationId,
      long expectedEmbryoVersion,
      Instant performedAt,
      String timezone,
      UUID operatorProfessionalId,
      String notes) {
    public PerformanceItem {
      StableIds.requireVersion7(itemId);
      StableIds.requireVersion7(transferId);
      StableIds.requireVersion7(reservationId);
      if (expectedEmbryoVersion < 0 || performedAt == null || operatorProfessionalId == null)
        throw rejected("INVALID_TRANSFER_ITEM");
      try {
        if (timezone == null || timezone.length() > 64) throw new DateTimeException("timezone");
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw rejected("INVALID_TRANSFER_TIMEZONE");
      }
      notes = optionalText(notes, 1000, "INVALID_TRANSFER_NOTES");
    }
  }

  public record ItemResult(
      UUID itemId, UUID subjectId, UUID embryoId, String status, long embryoVersion) {}

  public record Result(UUID batchId, List<ItemResult> items) {
    public Result {
      items = List.copyOf(items);
    }
  }

  private static void validateBatch(UUID id, List<?> items) {
    StableIds.requireVersion7(id);
    if (items == null || items.isEmpty() || items.size() > 100)
      throw rejected("INVALID_TRANSFER_BATCH_SIZE");
  }

  private static void unique(List<UUID> ids, String code) {
    if (new HashSet<>(ids).size() != ids.size()) throw rejected(code);
  }

  private static String optionalText(String value, int max, String code) {
    if (value == null) return null;
    if (value.isBlank() || value.length() > max) throw rejected(code);
    return value.strip();
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Transfer command is invalid");
  }
}
