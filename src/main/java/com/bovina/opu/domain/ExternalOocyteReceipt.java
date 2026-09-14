package com.bovina.opu.domain;

import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.UUID;

/** Declared external origin, without inventing an internal session or donor identity. */
public record ExternalOocyteReceipt(
    UUID id,
    Instant receivedAt,
    String sourceReference,
    UUID sourceFarmPropertyId,
    UUID ownerId,
    UUID collectingProfessionalId,
    Integer totalReceived,
    UUID sourceDocumentId,
    String notes) {
  public ExternalOocyteReceipt {
    StableIds.requireVersion7(id);
    if (receivedAt == null
        || sourceReference == null
        || sourceReference.isBlank()
        || sourceReference.length() > 500
        || totalReceived == null
        || totalReceived < 0
        || (notes != null && notes.length() > 2000))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_EXTERNAL_RECEIPT",
          "Receipt requires observed time, external source reference and a nonnegative count");
    receivedAt = receivedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }
}
