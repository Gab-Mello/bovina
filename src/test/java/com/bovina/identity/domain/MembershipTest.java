package com.bovina.identity.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.StableIds;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MembershipTest {
  private final StableIds ids = new StableIds();

  @Test
  void rejectsEmptyValidityInterval() {
    var now = Instant.parse("2026-09-09T00:00:00Z");
    assertThatThrownBy(
            () ->
                new OrganizationMembership(
                    ids.next(), ids.next(), ids.next(), MembershipRole.OPERATOR, now, now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readOnlyCannotCreateClientsOrManageMemberships() {
    assertThat(MembershipRole.READ_ONLY.permissions())
        .containsExactlyInAnyOrder(
            "client:read",
            "master-data:read",
            "opu:read",
            "semen:read",
            "fertilization:read",
            "embryology:read",
            "transfer:read",
            "inventory:read");
    assertThat(MembershipRole.READ_ONLY.permissions()).doesNotContain("opu:write");
    assertThat(MembershipRole.OPERATOR.permissions()).doesNotContain("membership:manage");
    assertThat(MembershipRole.OPERATOR.permissions()).doesNotContain("protocol:manage");
    assertThat(MembershipRole.OPERATOR.permissions()).doesNotContain("lineage:correct");
    assertThat(MembershipRole.READ_ONLY.permissions()).doesNotContain("transfer:write");
    assertThat(MembershipRole.READ_ONLY.permissions()).doesNotContain("inventory:write");
  }

  @Test
  void revocationDoesNotChangeIdentity() {
    var id = ids.next();
    var membership =
        new OrganizationMembership(
            id, ids.next(), ids.next(), MembershipRole.OPERATOR, Instant.now(), null);
    membership.revoke();
    assertThat(membership.id()).isEqualTo(id);
    assertThat(membership.status()).isEqualTo(OrganizationMembership.Status.REVOKED);
  }
}
