package com.bovina.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Source metadata belongs to the fact, not to the audit stream or client-reported event time. */
@Embeddable
public record DataProvenance(
    @Enumerated(EnumType.STRING) @Column(name = "origin_type", nullable = false, length = 32)
        Origin originType,
    @Column(name = "source_document_id") UUID sourceDocumentId,
    @Column(name = "import_batch_id") UUID importBatchId,
    @Column(name = "api_client_id") UUID apiClientId,
    @Column(name = "recorded_by", nullable = false) UUID recordedByUserId,
    @Column(name = "recorded_at", nullable = false) Instant recordedAt,
    @Column(name = "confirmed_by") UUID confirmedByUserId,
    @Column(name = "confirmed_at") Instant confirmedAt,
    @Column(name = "derivation_reference", length = 256) String derivationReference) {

  public enum Origin {
    MANUAL,
    IMPORT,
    API,
    AI_EXTRACTED_CONFIRMED,
    SYSTEM_DERIVED
  }

  public DataProvenance {
    Objects.requireNonNull(originType);
    Objects.requireNonNull(recordedByUserId);
    Objects.requireNonNull(recordedAt);
    if ((confirmedByUserId == null) != (confirmedAt == null))
      throw new IllegalArgumentException("Confirmation requires both actor and time");
    if (confirmedAt != null && confirmedAt.isAfter(recordedAt))
      throw new IllegalArgumentException("Confirmation cannot follow official recording");
    if (derivationReference != null
        && (derivationReference.isBlank() || derivationReference.length() > 256))
      throw new IllegalArgumentException("Invalid derivation reference");
    boolean valid =
        switch (originType) {
          case MANUAL ->
              importBatchId == null
                  && apiClientId == null
                  && derivationReference == null
                  && confirmedAt == null;
          case IMPORT ->
              importBatchId != null && apiClientId == null && derivationReference == null;
          case API -> apiClientId != null && importBatchId == null && derivationReference == null;
          case AI_EXTRACTED_CONFIRMED ->
              sourceDocumentId != null && confirmedByUserId != null && derivationReference == null;
          case SYSTEM_DERIVED ->
              derivationReference != null && importBatchId == null && apiClientId == null;
        };
    if (!valid)
      throw new IllegalArgumentException("Provenance does not support the declared origin");
  }

  public static DataProvenance manual(UUID actor, Instant recordedAt) {
    return new DataProvenance(Origin.MANUAL, null, null, null, actor, recordedAt, null, null, null);
  }
}
