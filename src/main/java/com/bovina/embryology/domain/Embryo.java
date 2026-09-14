package com.bovina.embryology.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Entity
@Table(name = "embryo")
public class Embryo {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID matingId;

  @Column(nullable = false, length = 160)
  private String humanCode;

  private UUID ownerId;

  @Column(nullable = false)
  private Instant identifiedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private AvailabilityStatus availabilityStatus;

  @Embedded private DataProvenance provenance;
  @Version private long version;

  protected Embryo() {}

  public Embryo(UUID tenant, Registration registration, DataProvenance provenance) {
    id = StableIds.requireVersion7(registration.id());
    organizationId = Objects.requireNonNull(tenant);
    matingId = StableIds.requireVersion7(registration.matingId());
    humanCode = requireText(registration.humanCode(), 160, "INVALID_EMBRYO_CODE");
    ownerId = registration.ownerId();
    identifiedAt =
        Objects.requireNonNull(registration.identifiedAt()).truncatedTo(ChronoUnit.MICROS);
    this.provenance = Objects.requireNonNull(provenance);
    availabilityStatus = AvailabilityStatus.AVAILABLE;
  }

  public void reserve() {
    require(AvailabilityStatus.AVAILABLE, "EMBRYO_NOT_AVAILABLE");
    availabilityStatus = AvailabilityStatus.RESERVED;
  }

  public void releaseReservation() {
    require(AvailabilityStatus.RESERVED, "EMBRYO_NOT_RESERVED");
    availabilityStatus = AvailabilityStatus.AVAILABLE;
  }

  public void discard() {
    if (availabilityStatus != AvailabilityStatus.AVAILABLE)
      throw conflict("EMBRYO_NOT_DISCARDABLE");
    availabilityStatus = AvailabilityStatus.DISCARDED;
  }

  private void require(AvailabilityStatus expected, String code) {
    if (availabilityStatus != expected) throw conflict(code);
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Embryo transition is not allowed");
  }

  private static String requireText(String value, int max, String code) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, code, "A valid embryo code is required");
    return value.strip();
  }

  public UUID id() {
    return id;
  }

  public UUID matingId() {
    return matingId;
  }

  public String humanCode() {
    return humanCode;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public Instant identifiedAt() {
    return identifiedAt;
  }

  public AvailabilityStatus availability() {
    return availabilityStatus;
  }

  public DataProvenance provenance() {
    return provenance;
  }

  public long version() {
    return version;
  }

  public record Registration(
      UUID id, UUID matingId, String humanCode, UUID ownerId, Instant identifiedAt) {}

  public enum AvailabilityStatus {
    AVAILABLE,
    RESERVED,
    TRANSFERRED,
    SHIPPED_OUT,
    DISCARDED,
    DESTROYED
  }
}
