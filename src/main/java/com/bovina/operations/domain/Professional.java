package com.bovina.operations.domain;

import com.bovina.platform.application.*;
import java.util.UUID;

public record Professional(
    UUID id, String name, Type professionalType, UUID linkedUserId, String status, long version) {
  public Professional {
    StableIds.requireVersion7(id);
    if (name == null
        || name.isBlank()
        || name.length() > 200
        || professionalType == null
        || !("ACTIVE".equals(status) || "INACTIVE".equals(status)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_PROFESSIONAL", "Invalid professional details");
    name = name.strip();
  }

  public void requireActive() {
    if (!status.equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "PROFESSIONAL_INACTIVE", "Professional is inactive");
  }

  public Professional deactivate(long expected) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "Professional has changed");
    return new Professional(id, name, professionalType, linkedUserId, "INACTIVE", version + 1);
  }

  public enum Type {
    VETERINARIAN,
    EMBRYOLOGIST,
    TECHNICIAN
  }
}
