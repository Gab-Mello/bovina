package com.bovina.semen.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.util.*;

public record SemenBatch(
    UUID id,
    String batchCode,
    UUID sireId,
    UUID producerEstablishmentId,
    String provenanceCode,
    String verificationStatus,
    String semenType,
    UUID ownerId,
    Instant receivedAt,
    String status,
    long version,
    DataProvenance provenance) {
  public SemenBatch {
    StableIds.requireVersion7(id);
    StableIds.requireVersion7(sireId);
    StableIds.requireVersion7(producerEstablishmentId);
    batchCode = require(batchCode, 160, "batch code");
    provenanceCode = code(provenanceCode, "provenance code");
    verificationStatus = code(verificationStatus, "verification status");
    if (semenType != null) semenType = code(semenType, "semen type");
    if (status == null) status = "ACTIVE";
    if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) invalid("status");
    Objects.requireNonNull(provenance);
    if (receivedAt != null)
      receivedAt = receivedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public void requireActive() {
    if (!status.equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "SEMEN_BATCH_INACTIVE", "Semen batch is inactive");
  }

  private static String code(String value, String field) {
    value = require(value, 48, field).toUpperCase(Locale.ROOT);
    if (!value.matches("[A-Z][A-Z0-9_]{0,47}")) invalid(field);
    return value;
  }

  private static String require(String value, int max, String field) {
    if (value == null || value.isBlank() || value.length() > max) invalid(field);
    return value.strip();
  }

  private static void invalid(String field) {
    throw new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, "INVALID_SEMEN_BATCH", "Invalid " + field);
  }
}
