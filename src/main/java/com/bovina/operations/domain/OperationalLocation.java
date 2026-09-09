package com.bovina.operations.domain;

import com.bovina.platform.application.*;
import java.time.*;
import java.util.UUID;

public record OperationalLocation(
    UUID id,
    UUID establishmentId,
    String name,
    Type type,
    String timezone,
    String status,
    long version) {
  public OperationalLocation {
    StableIds.requireVersion7(id);
    if (establishmentId == null
        || name == null
        || name.isBlank()
        || name.length() > 200
        || type == null
        || !("ACTIVE".equals(status) || "INACTIVE".equals(status)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_LOCATION", "Invalid operational location");
    name = name.strip();
    if (timezone != null) {
      try {
        if (timezone.length() > 64) throw new DateTimeException("length");
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED, "INVALID_TIMEZONE", "Use an IANA timezone");
      }
    }
  }

  public OperationalLocation deactivate(long expected) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Location has changed");
    return new OperationalLocation(
        id, establishmentId, name, type, timezone, "INACTIVE", version + 1);
  }

  public enum Type {
    LAB,
    COLLECTION_UNIT,
    STORAGE,
    OFFICE,
    OTHER
  }
}
