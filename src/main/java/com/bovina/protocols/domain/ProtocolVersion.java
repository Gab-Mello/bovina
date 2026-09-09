package com.bovina.protocols.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.*;
import java.util.UUID;

/** Published content identity is immutable; this is a registry, not a protocol editor. */
public record ProtocolVersion(
    UUID id,
    UUID definitionId,
    String revision,
    EffectivePeriod effectivePeriod,
    String contentReference,
    String checksum,
    UUID documentId,
    UUID publishedBy,
    Instant publishedAt) {
  public ProtocolVersion {
    StableIds.requireVersion7(id);
    if (definitionId == null
        || revision == null
        || revision.isBlank()
        || revision.length() > 40
        || effectivePeriod == null
        || contentReference == null
        || contentReference.isBlank()
        || contentReference.length() > 500
        || checksum == null
        || !checksum.matches("[0-9a-f]{64}")
        || publishedBy == null
        || publishedAt == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_PROTOCOL_VERSION",
          "Publication requires a revision, effective period, content reference and SHA-256 checksum");
    revision = revision.strip();
    contentReference = contentReference.strip();
  }

  public void requireApplicable(String definitionPurpose, String requestedPurpose, LocalDate on) {
    if (!definitionPurpose.equals(requestedPurpose)
        || on == null
        || on.isBefore(effectivePeriod.from())
        || (effectivePeriod.until() != null && !on.isBefore(effectivePeriod.until())))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "PROTOCOL_NOT_APPLICABLE",
          "Protocol purpose or effective period does not match this application");
  }
}
