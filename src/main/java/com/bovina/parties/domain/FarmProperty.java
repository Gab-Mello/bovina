package com.bovina.parties.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.Address;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "farm_property")
public class FarmProperty {
  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 200)
  private String name;

  private UUID ownerId;
  private UUID operatorId;
  @Embedded private Address address;

  @Column(length = 40)
  private String municipalityCode;

  @Column(length = 80)
  private String internalCode;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(nullable = false)
  private Instant recordedAt;

  @Column(nullable = false)
  private UUID recordedBy;

  @Version private Long version;

  protected FarmProperty() {}

  public FarmProperty(UUID tenant, Details details, UUID actor, Instant now) {
    id = details.id();
    organizationId = Objects.requireNonNull(tenant);
    name = details.name();
    ownerId = details.ownerId();
    operatorId = details.operatorId();
    address = details.address();
    municipalityCode = details.municipalityCode();
    internalCode = details.internalCode();
    status = "ACTIVE";
    recordedBy = Objects.requireNonNull(actor);
    recordedAt = Objects.requireNonNull(now);
  }

  public void archive(long expected) {
    if (version == null || version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "The property has changed");
    status = "ARCHIVED";
  }

  public UUID id() {
    return id;
  }

  public Long version() {
    return version;
  }

  public String status() {
    return status;
  }

  public Details details() {
    return new Details(id, name, ownerId, operatorId, address, municipalityCode, internalCode);
  }

  public record Details(
      UUID id,
      String name,
      UUID ownerId,
      UUID operatorId,
      Address address,
      String municipalityCode,
      String internalCode) {
    public Details {
      StableIds.requireVersion7(id);
      if (name == null
          || name.isBlank()
          || name.length() > 200
          || address == null
          || (municipalityCode != null && municipalityCode.length() > 40)
          || (internalCode != null && internalCode.length() > 80))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED, "INVALID_PROPERTY", "Invalid property details");
      name = name.strip();
    }
  }
}
