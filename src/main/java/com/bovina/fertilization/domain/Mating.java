package com.bovina.fertilization.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public record Mating(
    UUID id,
    UUID collectionId,
    UUID semenBatchId,
    int allocatedOocytes,
    Instant fertilizedAt,
    String method,
    UUID responsibleProfessionalId,
    Status status,
    long version,
    DataProvenance provenance) {
  public Mating {
    StableIds.requireVersion7(id);
    StableIds.requireVersion7(collectionId);
    StableIds.requireVersion7(semenBatchId);
    if (allocatedOocytes <= 0 || fertilizedAt == null || provenance == null)
      invalid("Allocation, fertilization time and provenance are required");
    method = code(method);
    fertilizedAt = fertilizedAt.truncatedTo(ChronoUnit.MICROS);
    status = status == null ? Status.FERTILIZED : status;
  }

  public void requireEmbryologyOpen() {
    if (status != Status.FERTILIZED)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "MATING_NOT_OPEN",
          "Mating is not open for embryology records");
  }

  private static String code(String value) {
    if (value == null || value.isBlank() || value.length() > 48) invalid("Invalid mating method");
    value = value.strip().toUpperCase(Locale.ROOT);
    if (!value.matches("[A-Z][A-Z0-9_]{0,47}")) invalid("Invalid mating method");
    return value;
  }

  private static void invalid(String message) {
    throw new ApplicationFailure(ApplicationFailure.Kind.REJECTED, "INVALID_MATING", message);
  }

  public enum Status {
    FERTILIZED,
    COMPLETED,
    CANCELLED
  }
}
