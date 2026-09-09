package com.bovina.parties.application;

import com.bovina.parties.domain.ClientType;
import java.time.Instant;
import java.util.UUID;

public record ClientView(
    UUID id,
    ClientType type,
    String displayName,
    long version,
    String originType,
    UUID recordedBy,
    Instant recordedAt) {}
