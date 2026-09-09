package com.bovina.parties.application;

import com.bovina.parties.domain.ClientType;
import com.bovina.platform.domain.Address;
import java.util.UUID;

public record CounterpartyView(
    UUID id,
    ClientType type,
    String displayName,
    String legalName,
    Address address,
    String status,
    long version) {}
