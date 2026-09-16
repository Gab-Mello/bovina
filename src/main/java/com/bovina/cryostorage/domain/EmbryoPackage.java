package com.bovina.cryostorage.domain;

import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.StableIds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "embryo_package")
@DynamicUpdate
public class EmbryoPackage {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "establishment_id", nullable = false)
  private UUID establishmentId;

  @Column(name = "package_code", nullable = false, length = 160)
  private String packageCode;

  @Column(name = "packaging_type", nullable = false, length = 80)
  private String packagingType;

  @Column(name = "owner_party_id")
  private UUID ownerPartyId;

  @Column(name = "packaged_at", nullable = false)
  private Instant packagedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(name = "sealed_at")
  private Instant sealedAt;

  @Column(name = "membership_updated_at")
  private Instant membershipUpdatedAt;

  @Column(name = "sealed_by")
  private UUID sealedBy;

  @Column(name = "current_location_id")
  private UUID currentLocationId;

  @Column(name = "last_movement_sequence", nullable = false)
  private long lastMovementSequence;

  @Version private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  protected EmbryoPackage() {}

  public EmbryoPackage(UUID tenant, Registration input, UUID actor, Instant now) {
    id = StableIds.requireVersion7(input.id());
    organizationId = Objects.requireNonNull(tenant);
    establishmentId = StableIds.requireVersion7(input.establishmentId());
    packageCode = text(input.packageCode(), 160, "INVALID_PACKAGE_CODE");
    packagingType = code(input.packagingType());
    ownerPartyId = input.ownerPartyId();
    packagedAt = Objects.requireNonNull(input.packagedAt());
    status = Status.DRAFT;
    createdBy = Objects.requireNonNull(actor);
    createdAt = Objects.requireNonNull(now);
  }

  public void seal(int activeMembers, UUID actor, Instant when) {
    if (status != Status.DRAFT) throw conflict("PACKAGE_NOT_DRAFT");
    if (activeMembers <= 0) throw conflict("EMPTY_PACKAGE_CANNOT_BE_SEALED");
    status = Status.SEALED;
    sealedBy = Objects.requireNonNull(actor);
    sealedAt = Objects.requireNonNull(when);
  }

  public void membersAdded(Instant when) {
    if (status != Status.DRAFT) throw conflict("PACKAGE_MEMBERSHIP_FINALIZED");
    membershipUpdatedAt = Objects.requireNonNull(when);
  }

  public void membersThawed(Instant when) {
    requireSealed();
    membershipUpdatedAt = Objects.requireNonNull(when);
  }

  public void applyPhysicalMovement(UUID from, UUID to) {
    requireSealed();
    if (!Objects.equals(currentLocationId, from))
      throw conflict("PACKAGE_NOT_IN_EXPECTED_LOCATION");
    if (Objects.equals(from, to)) throw conflict("PACKAGE_LOCATION_UNCHANGED");
    currentLocationId = to;
    lastMovementSequence++;
  }

  public void dispose() {
    requireSealed();
    status = Status.DISPOSED;
  }

  public void requireSealed() {
    if (status != Status.SEALED) throw conflict("PACKAGE_NOT_SEALED");
  }

  private static String text(String value, int max, String error) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, error, "Invalid package value");
    return value.strip();
  }

  private static String code(String value) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,79}"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "INVALID_PACKAGING_TYPE", "Invalid packaging type");
    return value;
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Package transition is not allowed");
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public UUID establishmentId() {
    return establishmentId;
  }

  public String packageCode() {
    return packageCode;
  }

  public String packagingType() {
    return packagingType;
  }

  public UUID ownerPartyId() {
    return ownerPartyId;
  }

  public Instant packagedAt() {
    return packagedAt;
  }

  public Status status() {
    return status;
  }

  public Instant sealedAt() {
    return sealedAt;
  }

  public UUID currentLocationId() {
    return currentLocationId;
  }

  public long lastMovementSequence() {
    return lastMovementSequence;
  }

  public long version() {
    return version;
  }

  public record Registration(
      UUID id,
      UUID establishmentId,
      String packageCode,
      String packagingType,
      UUID ownerPartyId,
      Instant packagedAt) {}

  public enum Status {
    DRAFT,
    SEALED,
    DISPOSED
  }
}
