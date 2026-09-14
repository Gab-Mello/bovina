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
          "lineage:correct")),
  OPERATOR(
      Set.of(
          "client:read",
          "client:create",
          "master-data:read",
          "master-data:write",
          "opu:read",
          "opu:write",
          "semen:read",
          "semen:write",
          "fertilization:read",
          "fertilization:write",
          "embryology:read",
          "embryology:write",
          "transfer:read",
          "transfer:write")),
  READ_ONLY(
      Set.of(
          "client:read",
          "master-data:read",
          "opu:read",
          "semen:read",
          "fertilization:read",
          "embryology:read",
          "transfer:read"));

  private final Set<String> permissions;

  MembershipRole(Set<String> permissions) {
    this.permissions = permissions;
  }

  public Set<String> permissions() {
    return permissions;
  }
}
