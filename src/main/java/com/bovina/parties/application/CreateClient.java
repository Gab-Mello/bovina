package com.bovina.parties.application;

import com.bovina.parties.domain.ClientType;
import com.bovina.platform.application.*;
import java.util.Objects;
import java.util.UUID;

public record CreateClient(UUID id, ClientType type, String displayName, CommandMetadata metadata) {
  public CreateClient {
    StableIds.requireVersion7(id);
    Objects.requireNonNull(type);
    Objects.requireNonNull(metadata);
    StableIds.requireVersion7(metadata.commandId());
    if (displayName == null || displayName.isBlank() || displayName.length() > 200)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_CLIENT_NAME",
          "A client name of at most 200 characters is required");
    displayName = displayName.strip();
  }
}
