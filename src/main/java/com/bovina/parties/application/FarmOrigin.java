package com.bovina.parties.application;

import com.bovina.platform.domain.Address;
import java.util.UUID;

public record FarmOrigin(
    UUID id,
    String name,
    Address address,
    String municipalityCode,
    String internalCode,
    UUID ownerId,
    UUID operatorId,
    long version) {}
