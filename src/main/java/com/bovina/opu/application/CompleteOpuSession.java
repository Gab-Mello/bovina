package com.bovina.opu.application;

import com.bovina.animals.application.DonorDirectory;
import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.infrastructure.*;
import com.bovina.parties.application.FarmProperties;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompleteOpuSession {
  private final TenantAccess access;
  private final OpuSessionRepository sessions;
  private final OocyteCollectionRepository collections;
  private final OpuFacts facts;
  private final FarmProperties farms;
  private final DonorDirectory donors;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public CompleteOpuSession(
      TenantAccess access,
      OpuSessionRepository sessions,
      OocyteCollectionRepository collections,
      OpuFacts facts,
      FarmProperties farms,
      DonorDirectory donors,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.sessions = sessions;
    this.collections = collections;
    this.facts = facts;
    this.farms = farms;
    this.donors = donors;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public OpuSessions.View complete(ExecutionContext c, UUID key, UUID id, long expected) {
    access.require(c, "opu:write");
    return receipts.replayOrExecute(
        c,
        key,
        "COMPLETE_OPU_V1",
        new Intent(id, expected),
        OpuSessions.View.class,
        () -> {
          var session = sessions.lock(c.tenantId(), id).orElseThrow(OpuSessions::missing);
          session.requireVersion(expected);
          session.requireWritable();
          var origin = farms.origin(c, session.registration().farmPropertyId(), false);
          facts.freezeFarm(c.tenantId(), id, origin);
          // Every collection writer locks this session first. Pages remain stable during
          // completion.
          for (int page = 0; ; page++) {
            var records = collections.page(c.tenantId(), id, PageRequest.of(page, 100));
            if (records.isEmpty()) break;
            var snapshots =
                donors.snapshots(
                    c.tenantId(), records.stream().map(r -> r.donorId()).distinct().toList());
            records.forEach(r -> r.complete());
            facts.freezeDonors(c.tenantId(), records, snapshots);
            collections.flush();
            audit.recordAll(
                records.stream()
                    .map(
                        r ->
                            new AuditEvent(
                                ids.next(),
                                c,
                                clock.instant(),
                                "COMPLETE",
                                "OOCYTE_COLLECTION",
                                r.id(),
                                r.version(),
                                null,
                                null,
                                "COMPLETED"))
                    .toList());
          }
          session.complete(
              expected,
              c.actorId(),
              clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
          sessions.flush();
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  clock.instant(),
                  "COMPLETE",
                  "OPU_SESSION",
                  id,
                  session.version(),
                  null,
                  null,
                  "COMPLETED"));
          return OpuSessions.view(session);
        });
  }

  private record Intent(UUID id, long expectedVersion) {}
}
