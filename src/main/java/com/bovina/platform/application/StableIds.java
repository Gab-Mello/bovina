package com.bovina.platform.application;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StableIds {
  public UUID next() {
    return UuidCreator.getTimeOrderedEpoch();
  }

  public static UUID requireVersion7(UUID id) {
    if (id == null || id.version() != 7 || id.variant() != 2) {
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_STABLE_ID", "A UUIDv7 identifier is required");
    }
    return id;
  }
}
