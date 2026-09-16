package com.bovina.distribution.domain;

import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.StableIds;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "shipment")
@DynamicUpdate
public class Shipment {
  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID establishmentId;

  @Column(nullable = false)
  private UUID destinationRecipientId;

  private UUID destinationPropertyId;

  @Column(nullable = false, length = 80)
  private String purpose;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Version private long version;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private UUID createdBy;

  protected Shipment() {}

  public Shipment(
      UUID tenant,
      UUID id,
      UUID establishment,
      UUID recipient,
      UUID property,
      String purpose,
      UUID actor,
      Instant now) {
    this.id = StableIds.requireVersion7(id);
    this.organizationId = tenant;
    this.establishmentId = StableIds.requireVersion7(establishment);
    this.destinationRecipientId = StableIds.requireVersion7(recipient);
    this.destinationPropertyId = property;
    if (property != null) StableIds.requireVersion7(property);
    if (purpose == null || !purpose.matches("[A-Z][A-Z0-9_]{0,79}"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_SHIPMENT_PURPOSE",
          "A movement purpose code is required");
    this.purpose = purpose;
    this.createdBy = actor;
    this.createdAt = now;
    this.status = Status.DRAFT;
  }

  public void requireDraft(long expected) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "STALE_SHIPMENT_VERSION", "Shipment changed");
    if (status != Status.DRAFT)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "SHIPMENT_NOT_DRAFT",
          "Dispatched and cancelled shipments cannot be rewritten");
  }

  public void dispatch(long expected) {
    requireDraft(expected);
    status = Status.SHIPPED;
  }

  public void cancel(long expected) {
    requireDraft(expected);
    status = Status.CANCELLED;
  }

  public UUID id() {
    return id;
  }

  public UUID establishmentId() {
    return establishmentId;
  }

  public UUID recipientId() {
    return destinationRecipientId;
  }

  public UUID propertyId() {
    return destinationPropertyId;
  }

  public String purpose() {
    return purpose;
  }

  public Status status() {
    return status;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public enum Status {
    DRAFT,
    SHIPPED,
    CANCELLED
  }
}
