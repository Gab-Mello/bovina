package com.bovina.protocols.domain;

import com.bovina.platform.application.*;
import java.util.UUID;

public record ProtocolDefinition(
    UUID id, String purpose, String name, String reference, String status, long version) {
  public ProtocolDefinition {
    StableIds.requireVersion7(id);
    if (purpose == null
        || !purpose.matches("[A-Z][A-Z0-9_]{0,47}")
        || name == null
        || name.isBlank()
        || name.length() > 200
        || (reference != null && reference.length() > 250)
        || !("ACTIVE".equals(status) || "INACTIVE".equals(status)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_PROTOCOL_DEFINITION",
          "Protocol purpose, name or reference is invalid");
    name = name.strip();
  }

  public void requireActive() {
    if (!status.equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "PROTOCOL_INACTIVE",
          "Protocol is inactive for new applications");
  }

  public ProtocolDefinition deactivate(long expected) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "Protocol definition has changed");
    return new ProtocolDefinition(id, purpose, name, reference, "INACTIVE", version + 1);
  }
}
