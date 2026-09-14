package com.bovina.opu.application;

import com.bovina.animals.application.DonorDirectory.Donor;
import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.domain.*;
import com.bovina.opu.infrastructure.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OocyteCollections {
  private final TenantAccess access;
  private final OocyteCollectionRepository collections;
  private final OpuSessionRepository sessions;
  private final OpuFacts facts;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public OocyteCollections(
      TenantAccess access,
      OocyteCollectionRepository collections,
      OpuSessionRepository sessions,
      OpuFacts facts,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.collections = collections;
    this.sessions = sessions;
    this.facts = facts;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "opu:read");
    return view(find(c, id));
  }

  @Transactional(readOnly = true)
  public PageResult<View> page(ExecutionContext c, UUID session, SearchPage page) {
    access.require(c, "opu:read");
    sessions.findByOrganizationIdAndId(c.tenantId(), session).orElseThrow(OpuSessions::missing);
    return new PageResult<>(
        collections.page(c.tenantId(), session, PageRequest.of(page.page(), page.size())).stream()
            .map(OocyteCollections::view)
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional(readOnly = true)
  public Donor identitySnapshot(ExecutionContext c, UUID id) {
    access.require(c, "opu:read");
    find(c, id);
    var snapshot = facts.donorSnapshot(c.tenantId(), id);
    if (snapshot == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND,
          "SNAPSHOT_NOT_AVAILABLE",
          "Collection is not finalized");
    return snapshot;
  }

  @Transactional
  public View correct(ExecutionContext c, UUID key, UUID id, Correction input) {
    access.require(c, "opu:write");
    return receipts.replayOrExecute(
        c,
        key,
        "CORRECT_COLLECTION_V1",
        new Intent(id, input),
        View.class,
        () -> {
          var found = find(c, id);
          var session =
              sessions.lock(c.tenantId(), found.sessionId()).orElseThrow(OpuSessions::missing);
          session.requireWritable();
          var collection =
              collections.lock(c.tenantId(), id).orElseThrow(OocyteCollections::missing);
          // Only unfinalized collections can change here; they cannot yet have allocations.
          var before = collection.counts();
          collection.correct(
              input.expectedVersion(), input.counts(), input.notes(), input.reason(), 0);
          collections.flush();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  clock.instant(),
                  "CORRECT",
                  "OOCYTE_COLLECTION",
                  id,
                  collection.version(),
                  input.reason(),
                  countState(before),
                  countState(collection.counts())));
          return view(collection);
        });
  }

  private OocyteCollection find(ExecutionContext c, UUID id) {
    return collections
        .findByOrganizationIdAndId(c.tenantId(), id)
        .orElseThrow(OocyteCollections::missing);
  }

  private static String countState(OocyteCounts counts) {
    return "total="
        + counts.totalRecovered()
        + ",viable="
        + counts.viable()
        + ",follicles="
        + counts.folliclesAspirated();
  }

  static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "COLLECTION_NOT_FOUND", "Oocyte collection not found");
  }

  private static View view(OocyteCollection r) {
    return new View(r.sessionId(), r.registration(), r.status(), r.version(), r.provenance());
  }

  public record View(
      UUID sessionId,
      OocyteCollection.Registration registration,
      OocyteCollection.Status status,
      long version,
      DataProvenance provenance) {}

  public record Correction(
      long expectedVersion, OocyteCounts counts, String notes, String reason) {
    public Correction {
      if (expectedVersion < 0 || counts == null || reason == null || reason.isBlank() || reason.length()>500 || (notes!=null && notes.length()>2000))
        throw new ApplicationFailure(ApplicationFailure.Kind.REJECTED,"INVALID_COLLECTION_CORRECTION","Version, counts and correction reason are required");
    }
  }

  private record Intent(UUID id, Correction correction) {}
}
