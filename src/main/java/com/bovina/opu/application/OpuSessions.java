package com.bovina.opu.application;

import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.domain.OpuSession;
import com.bovina.opu.infrastructure.*;
import com.bovina.parties.application.FarmOrigin;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpuSessions {
  private final TenantAccess access;
  private final OpuSessionRepository sessions;
  private final OpuRegistrationReferences references;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;
  private final OpuFacts facts;

  public OpuSessions(
      TenantAccess access,
      OpuSessionRepository sessions,
      OpuRegistrationReferences references,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock,
      OpuFacts facts) {
    this.access = access;
    this.sessions = sessions;
    this.references = references;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
    this.facts = facts;
  }

  @Transactional
  public View open(ExecutionContext c, UUID key, OpuSession.Registration input) {
    access.require(c, "opu:write");
    return receipts.replayOrExecute(
        c,
        key,
        "OPEN_OPU_V1",
        input,
        View.class,
        () -> {
          references.validate(c, input);
          var s =
              sessions.save(
                  new OpuSession(
                      c.tenantId(),
                      input,
                      DataProvenance.manual(
                          c.actorId(), clock.instant().truncatedTo(ChronoUnit.MICROS))));
          sessions.flush();
          record(c, s, "OPEN");
          return view(s);
        });
  }

  @Transactional
  public View transition(ExecutionContext c, UUID key, UUID id, long expected, Action action) {
    access.require(c, "opu:write");
    return receipts.replayOrExecute(
        c,
        key,
        "TRANSITION_OPU_V1",
        new Transition(id, expected, action),
        View.class,
        () -> {
          var s = sessions.lock(c.tenantId(), id).orElseThrow(OpuSessions::missing);
          if (action == Action.START) s.start(expected);
          else s.cancel(expected);
          sessions.flush();
          record(c, s, action.name());
          return view(s);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "opu:read");
    return view(find(c, id));
  }

  @Transactional(readOnly = true)
  public PageResult<View> page(ExecutionContext c, SearchPage page) {
    access.require(c, "opu:read");
    return new PageResult<>(
        sessions.page(c.tenantId(), PageRequest.of(page.page(), page.size())).stream()
            .map(OpuSessions::view)
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional(readOnly = true)
  public Summary summary(ExecutionContext c, UUID id) {
    access.require(c, "opu:read");
    var s = find(c, id);
    var counts = facts.summary(c.tenantId(), id);
    return new Summary(
        s.id(),
        s.status(),
        counts.collections(),
        counts.totalRecovered(),
        counts.viable(),
        facts.farmSnapshot(c.tenantId(), id));
  }

  private OpuSession find(ExecutionContext c, UUID id) {
    return sessions.findByOrganizationIdAndId(c.tenantId(), id).orElseThrow(OpuSessions::missing);
  }

  private void record(ExecutionContext c, OpuSession s, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "OPU_SESSION",
            s.id(),
            s.version(),
            null,
            null,
            s.status().name()));
  }

  public static View view(OpuSession s) {
    return new View(
        s.registration(),
        s.status(),
        s.version(),
        s.provenance(),
        s.completedAt(),
        s.completedBy());
  }

  public static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "OPU_SESSION_NOT_FOUND", "OPU session not found");
  }

  public enum Action {
    START,
    CANCEL
  }

  private record Transition(UUID id, long expectedVersion, Action action) {}

  public record View(
      OpuSession.Registration registration,
      OpuSession.Status status,
      long version,
      DataProvenance provenance,
      Instant completedAt,
      UUID completedBy) {}

  public record Summary(
      UUID id,
      OpuSession.Status status,
      long collections,
      long totalRecovered,
      long viable,
      FarmOrigin farmSnapshot) {}
}
