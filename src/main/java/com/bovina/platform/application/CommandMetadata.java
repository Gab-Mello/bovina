package com.bovina.platform.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-stable retry identity and reported event time; neither grants authority or commit
 * ordering.
 */
public record CommandMetadata(UUID commandId, Instant occurredAt) {
  public CommandMetadata {
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
