package com.bovina.transfer.domain;

import com.bovina.platform.application.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "embryo_transfer_reservation")
@DynamicUpdate
public class TransferReservation {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID embryoId;

  @Column(nullable = false)
  private UUID recipientCycleId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(nullable = false)
  private UUID reservedBy;

  @Column(nullable = false)
  private Instant reservedAt;

  private UUID endedBy;
  private Instant endedAt;

  @Column(length = 500)
  private String endReason;

  @Version private long version;

  protected TransferReservation() {}

  public TransferReservation(
      UUID tenant, UUID id, UUID embryoId, UUID recipientCycleId, UUID actor, Instant now) {
    this.id = StableIds.requireVersion7(id);
    organizationId = Objects.requireNonNull(tenant);
    this.embryoId = StableIds.requireVersion7(embryoId);
    this.recipientCycleId = StableIds.requireVersion7(recipientCycleId);
    reservedBy = Objects.requireNonNull(actor);
    reservedAt = Objects.requireNonNull(now);
    status = Status.ACTIVE;
  }

  public void consume(UUID actor, Instant now) {
    requireActive();
    status = Status.CONSUMED;
    endedBy = Objects.requireNonNull(actor);
    endedAt = Objects.requireNonNull(now);
    endReason = "TRANSFER_PERFORMED";
  }

  public void cancel(UUID actor, Instant now, String reason) {
    requireActive();
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw rejected("RESERVATION_CANCEL_REASON_REQUIRED", "A cancellation reason is required");
    status = Status.CANCELLED;
    endedBy = Objects.requireNonNull(actor);
    endedAt = Objects.requireNonNull(now);
    endReason = reason.strip();
  }

  public void requireActive() {
    if (status != Status.ACTIVE)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "TRANSFER_RESERVATION_NOT_ACTIVE",
          "Transfer reservation is not active");
  }

  public UUID id() {
    return id;
  }

  public UUID embryoId() {
    return embryoId;
  }

  public UUID recipientCycleId() {
    return recipientCycleId;
  }

  public Status status() {
    return status;
  }

  public UUID reservedBy() {
    return reservedBy;
  }

  public Instant reservedAt() {
    return reservedAt;
  }

  public UUID endedBy() {
    return endedBy;
  }

  public Instant endedAt() {
    return endedAt;
  }

  public String endReason() {
    return endReason;
  }

  public long version() {
    return version;
  }

  public enum Status {
    ACTIVE,
    CANCELLED,
    CONSUMED
  }

  private static ApplicationFailure rejected(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, message);
  }
}
