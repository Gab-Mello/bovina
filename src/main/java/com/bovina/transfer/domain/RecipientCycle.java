package com.bovina.transfer.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(name = "recipient_cycle")
public class RecipientCycle {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "recipient_animal_id", nullable = false)
  private UUID recipientAnimalId;

  private UUID protocolVersionId;

  @Column(nullable = false)
  private LocalDate openedOn;

  private LocalDate closedOn;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(length = 1000)
  private String notes;

  @Column(length = 500)
  private String closureReason;

  @Embedded private DataProvenance provenance;
  @Version private long version;

  protected RecipientCycle() {}

  public RecipientCycle(UUID tenant, Registration input, DataProvenance provenance) {
    id = StableIds.requireVersion7(input.id());
    organizationId = Objects.requireNonNull(tenant);
    recipientAnimalId = StableIds.requireVersion7(input.recipientAnimalId());
    protocolVersionId = input.protocolVersionId();
    openedOn = Objects.requireNonNull(input.openedOn());
    notes = optionalText(input.notes(), 1000, "INVALID_RECIPIENT_CYCLE_NOTES");
    this.provenance = Objects.requireNonNull(provenance);
    status = Status.OPEN;
  }

  public void requireOpen() {
    if (status != Status.OPEN)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "RECIPIENT_CYCLE_NOT_OPEN",
          "Recipient cycle is not open");
  }

  public void close(long expectedVersion, LocalDate date, String reason) {
    requireVersion(expectedVersion);
    requireOpen();
    if (date == null || date.isBefore(openedOn))
      throw rejected("INVALID_CYCLE_CLOSE_DATE", "Cycle close date cannot precede its opening");
    closedOn = date;
    closureReason = requiredText(reason, 500, "CYCLE_CLOSE_REASON_REQUIRED");
    status = Status.CLOSED;
  }

  private void requireVersion(long expected) {
    if (version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "STALE_RECIPIENT_CYCLE_VERSION",
          "Recipient cycle version has changed");
  }

  public UUID id() {
    return id;
  }

  public UUID recipientAnimalId() {
    return recipientAnimalId;
  }

  public UUID protocolVersionId() {
    return protocolVersionId;
  }

  public LocalDate openedOn() {
    return openedOn;
  }

  public LocalDate closedOn() {
    return closedOn;
  }

  public Status status() {
    return status;
  }

  public String notes() {
    return notes;
  }

  public String closureReason() {
    return closureReason;
  }

  public DataProvenance provenance() {
    return provenance;
  }

  public long version() {
    return version;
  }

  public enum Status {
    OPEN,
    CLOSED,
    CANCELLED
  }

  public record Registration(
      UUID id, UUID recipientAnimalId, UUID protocolVersionId, LocalDate openedOn, String notes) {}

  private static String requiredText(String value, int max, String code) {
    if (value == null || value.isBlank() || value.length() > max)
      throw rejected(code, "Required text is invalid");
    return value.strip();
  }

  private static String optionalText(String value, int max, String code) {
    if (value == null) return null;
    if (value.isBlank() || value.length() > max) throw rejected(code, "Optional text is invalid");
    return value.strip();
  }

  private static ApplicationFailure rejected(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, message);
  }
}
