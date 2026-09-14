package com.bovina.opu.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "opu_session")
public class OpuSession {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID establishmentId;

  private UUID operationalLocationId;

  @Column(nullable = false)
  private UUID farmPropertyId;

  private UUID clientId;

  @Column(nullable = false)
  private UUID leadProfessionalId;

  @Column(nullable = false)
  private Instant performedAt;

  @Column(nullable = false, length = 80)
  private String timezone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(length = 2000)
  private String notes;

  @Embedded private DataProvenance provenance;
  @Version private long version;
  private Instant completedAt;
  private UUID completedBy;

  protected OpuSession() {}

  public OpuSession(UUID tenant, Registration r, DataProvenance provenance) {
    this.id = r.id();
    this.organizationId = Objects.requireNonNull(tenant);
    this.establishmentId = r.establishmentId();
    this.operationalLocationId = r.operationalLocationId();
    this.farmPropertyId = r.farmPropertyId();
    this.clientId = r.clientId();
    this.leadProfessionalId = r.leadProfessionalId();
    this.performedAt = r.performedAt();
    this.timezone = r.timezone();
    this.notes = r.notes();
    this.provenance = Objects.requireNonNull(provenance);
    this.status = Status.DRAFT;
  }

  public void start(long expected) {
    requireVersion(expected);
    requireStatus(Status.DRAFT);
    status = Status.IN_PROGRESS;
  }

  public void cancel(long expected) {
    requireVersion(expected);
    if (status != Status.DRAFT && status != Status.IN_PROGRESS) throw conflict("SESSION_CLOSED");
    status = Status.CANCELLED;
  }

  public void complete(long expected, UUID actor, Instant now) {
    requireVersion(expected);
    requireWritable();
    status = Status.COMPLETED;
    completedBy = Objects.requireNonNull(actor);
    completedAt = Objects.requireNonNull(now);
  }

  public void requireWritable() {
    requireStatus(Status.IN_PROGRESS);
  }

  public void requireVersion(long expected) {
    if (expected != version) throw conflict("STALE_SESSION_VERSION");
  }

  private void requireStatus(Status expected) {
    if (status != expected) throw conflict("INVALID_SESSION_TRANSITION");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT,
        code,
        "Session state or version does not permit this command");
  }

  public UUID id() {
    return id;
  }

  public UUID tenantId() {
    return organizationId;
  }

  public Status status() {
    return status;
  }

  public long version() {
    return version;
  }

  public DataProvenance provenance() {
    return provenance;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public UUID completedBy() {
    return completedBy;
  }

  public Registration registration() {
    return new Registration(
        id,
        establishmentId,
        operationalLocationId,
        farmPropertyId,
        clientId,
        leadProfessionalId,
        performedAt,
        timezone,
        notes);
  }

  public enum Status {
    DRAFT,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
  }

  public record Registration(
      UUID id,
      UUID establishmentId,
      UUID operationalLocationId,
      UUID farmPropertyId,
      UUID clientId,
      UUID leadProfessionalId,
      Instant performedAt,
      String timezone,
      String notes) {
    public Registration {
      StableIds.requireVersion7(id);
      if (establishmentId == null
          || farmPropertyId == null
          || leadProfessionalId == null
          || performedAt == null
          || timezone == null
          || timezone.length() > 80
          || (notes != null && notes.length() > 2000))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_OPU_SESSION",
            "Session references, time and timezone are required");
      try {
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED, "INVALID_TIMEZONE", "Use an IANA timezone");
      }
    }
  }
}
