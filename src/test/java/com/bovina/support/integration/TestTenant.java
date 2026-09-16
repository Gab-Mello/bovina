package com.bovina.support.integration;

import java.util.UUID;

public record TestTenant(UUID id, UUID actorId, String subject, String token) {}
