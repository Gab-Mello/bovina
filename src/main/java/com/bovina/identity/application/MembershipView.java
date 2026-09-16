package com.bovina.identity.application;

import java.util.Set;
import java.util.UUID;

public record MembershipView(UUID organizationId, UUID actorId, Set<String> permissions) {
  public MembershipView {
    permissions = Set.copyOf(permissions);
  }
}
