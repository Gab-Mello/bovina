package com.bovina.documents.domain;

import com.bovina.platform.application.*;
import java.util.UUID;

/** A declared external source revision, not evidence that a file has been uploaded or verified. */
public record DocumentReference(
    UUID id, String type, String reference, String revision, String checksum, UUID supersedesId) {
  public DocumentReference {
    StableIds.requireVersion7(id);
    if (type == null
        || type.isBlank()
        || type.length() > 64
        || reference == null
        || reference.isBlank()
        || reference.length() > 500
        || revision == null
        || revision.isBlank()
        || revision.length() > 80
        || (checksum != null && !checksum.matches("[0-9a-f]{64}"))
        || id.equals(supersedesId))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_DOCUMENT_REFERENCE",
          "A source reference and revision are required; checksum must be SHA-256 when supplied");
    type = type.strip();
    reference = reference.strip();
    revision = revision.strip();
  }
}
