package com.bovina.opu.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "oocyte_collection")
public class OocyteCollection {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID opuSessionId;

  @Column(nullable = false)
  private UUID donorId;

  @Column(nullable = false)
  private Instant collectedAt;

  @Column(nullable = false)
  private int totalRecovered;

  @Column(nullable = false)
  private int viable;

  private Integer folliclesAspirated;

  @Column(length = 2000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Embedded private DataProvenance provenance;
  @Version private long version;

  protected OocyteCollection() {}

  public OocyteCollection(UUID tenant, UUID session, Registration r, DataProvenance provenance) {
    id = r.id();
    organizationId = Objects.requireNonNull(tenant);
    opuSessionId = Objects.requireNonNull(session);
    donorId = r.donorId();
    collectedAt = r.collectedAt();
    notes = r.notes();
    apply(r.counts());
    this.provenance = Objects.requireNonNull(provenance);
    status = Status.RECORDED;
  }

  public void correct(
      long expected, OocyteCounts counts, String notes, String reason, long allocated) {
    requireVersion(expected);
    if (status != Status.RECORDED)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "FINALIZED_COLLECTION_REQUIRES_CORRECTION",
          "Finalized facts cannot be rewritten; use a historical correction workflow");
    if (reason == null
        || reason.isBlank()
        || reason.length() > 500
        || (notes != null && notes.length() > 2000))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "CORRECTION_REASON_REQUIRED",
          "A correction reason is required");
    counts.availableAfter(allocated);
    apply(counts);
    this.notes = notes;
  }

  private void apply(OocyteCounts counts) {
    totalRecovered = counts.totalRecovered();
    viable = counts.viable();
    folliclesAspirated = counts.folliclesAspirated();
  }

  public void complete() {
    if (status != Status.RECORDED)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "COLLECTION_ALREADY_FINALIZED",
          "Collection is already finalized");
    status = Status.COMPLETED;
  }

  public void requireVersion(long expected) {
    if (expected != version)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "STALE_COLLECTION_VERSION",
          "Collection version has changed");
  }

  public void requireAllocatable() {
    if (status != Status.COMPLETED)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "COLLECTION_NOT_COMPLETED",
          "Only finalized collections can be allocated");
  }

  public UUID id() {
    return id;
  }

  public UUID sessionId() {
    return opuSessionId;
  }

  public UUID donorId() {
    return donorId;
  }

  public long version() {
    return version;
  }

  public Status status() {
    return status;
  }

  public DataProvenance provenance() {
    return provenance;
  }

  public OocyteCounts counts() {
    return new OocyteCounts(totalRecovered, viable, folliclesAspirated);
  }

  public Registration registration() {
    return new Registration(id, donorId, collectedAt, counts(), notes);
  }

  public enum Status {
    RECORDED,
    COMPLETED
  }

  public record Registration(
      UUID id, UUID donorId, Instant collectedAt, OocyteCounts counts, String notes) {
    public Registration {
      StableIds.requireVersion7(id);
      if (donorId == null
          || collectedAt == null
          || counts == null
          || (notes != null && notes.length() > 2000))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_COLLECTION",
            "Donor, collection time and counts are required");
    }
  }
}
