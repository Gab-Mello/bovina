package com.bovina.parties.application;

import com.bovina.parties.domain.ClientType;
import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RegisterCounterparty(
    UUID id, ClientType type, String displayName, Long expectedVersion, Instant occurredAt) {
  public RegisterCounterparty {
    StableIds.requireVersion7(id);
    Objects.requireNonNull(type);
    Objects.requireNonNull(occurredAt);
    if (displayName == null
        || displayName.isBlank()
        || displayName.length() > 200
        || (expectedVersion != null && expectedVersion < 0))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_COUNTERPARTY", "Invalid counterparty details");
    displayName = displayName.strip();
  }
}
