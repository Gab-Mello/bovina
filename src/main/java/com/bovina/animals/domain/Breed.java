package com.bovina.animals.domain;

import com.bovina.platform.application.*;
import java.util.UUID;

public record Breed(UUID id, String name, String code, String status, long version) {
  public Breed {
    StableIds.requireVersion7(id);
    if (name == null
        || name.isBlank()
        || name.length() > 200
        || (code != null && (code.isBlank() || code.length() > 80))
        || !("ACTIVE".equals(status) || "INACTIVE".equals(status)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_BREED", "Invalid breed name or code");
    name = name.strip();
    if (code != null) code = code.strip();
  }

  public Breed deactivate(long expected) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Breed has changed");
    return new Breed(id, name, code, "INACTIVE", version + 1);
  }
}
