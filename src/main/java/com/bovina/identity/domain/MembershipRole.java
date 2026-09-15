package com.bovina.identity.domain;

import java.util.Set;

public enum MembershipRole {
  ORG_ADMIN(
      Set.of(
          "client:read",
          "client:create",
          "membership:manage",
          "master-data:read",
          "master-data:write",
          "documents:read",
          "documents:write",
          "compliance:read",
          "compliance:manage",
          "protocol:manage",
          "opu:read",
          "opu:write",
          "semen:read",
          "semen:write",
          "fertilization:read",
          "fertilization:write",
          "embryology:read",
          "embryology:write",
          "transfer:read",
          "transfer:write",
          "inventory:read",
          "inventory:write",
          "inventory:adjust",
          "lineage:correct")),
  OPERATOR(
      Set.of(
          "client:read",
          "client:create",
          "master-data:read",
          "master-data:write",
          "documents:read",
          "documents:write",
          "compliance:read",
          "opu:read",
          "opu:write",
          "semen:read",
          "semen:write",
          "fertilization:read",
          "fertilization:write",
          "embryology:read",
          "embryology:write",
          "transfer:read",
          "transfer:write",
          "inventory:read",
          "inventory:write")),
  READ_ONLY(
      Set.of(
          "client:read",
          "master-data:read",
          "documents:read",
          "compliance:read",
          "opu:read",
          "semen:read",
          "fertilization:read",
          "embryology:read",
          "transfer:read",
          "inventory:read"));

  private final Set<String> permissions;

  MembershipRole(Set<String> permissions) {
    this.permissions = permissions;
  }

  public Set<String> permissions() {
    return permissions;
  }
}
