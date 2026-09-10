package com.bovina.operations.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.Address;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "establishment")
public class Establishment {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 200)
  private String legalDisplayName;

  @Column(nullable = false, length = 24)
  private String type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private OperatingMode operatingMode;

  @Embedded private Address address;

  @Column(length = 120)
  private String registrationIssuer;

  @Column(length = 120)
  private String registrationNumber;

  @Column(length = 250)
  private String registrationReference;

  private UUID registrationDocumentId;

  private LocalDate registrationValidFrom;
  private LocalDate registrationValidUntil;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "establishment_capability",
      joinColumns = {
        @JoinColumn(name = "establishment_id", referencedColumnName = "id"),
        @JoinColumn(name = "organization_id", referencedColumnName = "organization_id")
      })
  @Enumerated(EnumType.STRING)
  @Column(name = "capability", nullable = false, length = 32)
  private Set<Capability> capabilities = new HashSet<>();

  @Column(nullable = false, length = 16)
  private String status;

  @Column(nullable = false)
  private UUID recordedBy;

  @Column(nullable = false)
  private Instant recordedAt;

  @Version private Long version;

  protected Establishment() {}

  public Establishment(UUID tenant, Details input, UUID actor, Instant now) {
    id = input.id();
    organizationId = Objects.requireNonNull(tenant);
    legalDisplayName = input.legalDisplayName();
    type = "CPIVE";
    operatingMode = input.operatingMode();
    address = input.address();
    registrationIssuer = input.registrationIssuer();
    registrationNumber = input.registrationNumber();
    registrationReference = input.registrationReference();
    registrationDocumentId = input.registrationDocumentId();
    registrationValidFrom = input.registrationValidFrom();
    registrationValidUntil = input.registrationValidUntil();
    capabilities.addAll(input.capabilities());
    status = "ACTIVE";
    recordedBy = Objects.requireNonNull(actor);
    recordedAt = Objects.requireNonNull(now);
  }

  public void requireActive() {
    if (!status.equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "ESTABLISHMENT_INACTIVE", "Establishment is inactive");
  }

  public void deactivate(long expected) {
    if (version == null || version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "Establishment has changed");
    status = "INACTIVE";
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
    return new Details(
        id,
        legalDisplayName,
        operatingMode,
        address,
        registrationIssuer,
        registrationNumber,
        registrationReference,
        registrationDocumentId,
        registrationValidFrom,
        registrationValidUntil,
        capabilities);
  }

  public enum OperatingMode {
    COMMERCIAL,
    OWN_HERD_ONLY
  }

  public enum Capability {
    OOCYTE_COLLECTION,
    EMBRYO_PRODUCTION,
    CRYOSTORAGE,
    TRANSFER
  }

  public record Details(
      UUID id,
      String legalDisplayName,
      OperatingMode operatingMode,
      Address address,
      String registrationIssuer,
      String registrationNumber,
      String registrationReference,
      UUID registrationDocumentId,
      LocalDate registrationValidFrom,
      LocalDate registrationValidUntil,
      Set<Capability> capabilities) {
    public Details {
      StableIds.requireVersion7(id);
      capabilities =
          capabilities == null || capabilities.isEmpty()
              ? Set.of()
              : java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(capabilities));
      if (registrationValidUntil != null
          && (registrationValidFrom == null
              || !registrationValidUntil.isAfter(registrationValidFrom)))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_REGISTRATION_PERIOD",
            "Registration end must be later than its start");
      if (legalDisplayName == null
          || legalDisplayName.isBlank()
          || legalDisplayName.length() > 200
          || operatingMode == null
          || address == null
          || (registrationIssuer == null) != (registrationNumber == null)
          || (registrationIssuer != null
              && (registrationIssuer.isBlank()
                  || registrationIssuer.length() > 120
                  || registrationNumber.isBlank()
                  || registrationNumber.length() > 120))
          || (registrationReference != null && registrationReference.length() > 250))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_ESTABLISHMENT",
            "Invalid establishment details or registration reference");
      legalDisplayName = legalDisplayName.strip();
    }
  }
}
