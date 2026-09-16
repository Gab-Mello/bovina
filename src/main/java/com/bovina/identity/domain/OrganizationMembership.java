package com.bovina.identity.domain;

import com.bovina.platform.application.StableIds;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "organization_membership")
public class OrganizationMembership {
  public enum Status {
    ACTIVE,
    REVOKED
  }

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_account_id", nullable = false)
  private UUID userAccountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private MembershipRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_until")
  private Instant validUntil;

  @Version private Long version;

  protected OrganizationMembership() {}

  public OrganizationMembership(
      UUID id,
      UUID organizationId,
      UUID userAccountId,
      MembershipRole role,
      Instant validFrom,
      Instant validUntil) {
    this.id = StableIds.requireVersion7(id);
    this.organizationId = Objects.requireNonNull(organizationId);
    this.userAccountId = Objects.requireNonNull(userAccountId);
    this.role = Objects.requireNonNull(role);
    this.validFrom = Objects.requireNonNull(validFrom);
    if (validUntil != null && !validUntil.isAfter(validFrom))
      throw new IllegalArgumentException("Membership interval must be positive");
    this.validUntil = validUntil;
    this.status = Status.ACTIVE;
  }

  public UUID id() {
    return id;
  }

  public Long version() {
    return version;
  }

  public MembershipRole role() {
    return role;
  }

  public Status status() {
    return status;
  }

  public void revoke() {
    status = Status.REVOKED;
  }
}
