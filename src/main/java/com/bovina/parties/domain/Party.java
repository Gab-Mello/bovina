package com.bovina.parties.domain;

import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.StableIds;
import com.bovina.platform.domain.Address;
import com.bovina.platform.domain.DataProvenance;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "party")
public class Party {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ClientType type;

  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Embedded private DataProvenance provenance;

  @Column(length = 200)
  private String legalName;

  @Embedded private Address address;
  @Version private Long version;

  protected Party() {}

  public static Party registerClient(
      UUID id,
      UUID tenant,
      ClientType type,
      String name,
      Instant occurredAt,
      DataProvenance provenance) {
    if (name == null || name.isBlank() || name.length() > 200)
      throw new IllegalArgumentException("Invalid client name");
    var party = new Party();
    party.id = StableIds.requireVersion7(id);
    party.organizationId = Objects.requireNonNull(tenant);
    party.type = Objects.requireNonNull(type);
    party.displayName = name.strip();
    party.status = "ACTIVE";
    party.occurredAt = Objects.requireNonNull(occurredAt);
    party.provenance = Objects.requireNonNull(provenance);
    return party;
  }

  public UUID id() {
    return id;
  }

  public void requireActive() {
    if (!status.equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "COUNTERPARTY_ARCHIVED",
          "An archived counterparty cannot receive new assignments");
  }

  public void requireVersion(long expected) {
    if (version == null || version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "The record has changed; reload before editing");
  }

  public void updateDetails(long expected, String name, String legalName, Address address) {
    requireVersion(expected);
    requireActive();
    if (name == null
        || name.isBlank()
        || name.length() > 200
        || (legalName != null && legalName.length() > 200))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_COUNTERPARTY_NAME",
          "Invalid counterparty name");
    this.displayName = name.strip();
    this.legalName = legalName;
    this.address = address;
  }

  public void archive(long expected) {
    requireVersion(expected);
    status = "ARCHIVED";
  }

  public String status() {
    return status;
  }

  public String legalName() {
    return legalName;
  }

  public Address address() {
    return address;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public ClientType type() {
    return type;
  }

  public String displayName() {
    return displayName;
  }

  public DataProvenance provenance() {
    return provenance;
  }

  public Long version() {
    return version;
  }
}
