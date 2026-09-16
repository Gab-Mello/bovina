package com.bovina.parties.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.domain.Party;
import com.bovina.parties.infrastructure.ClientCommandStore;
import com.bovina.parties.infrastructure.PartyRepository;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.StableIds;
import com.bovina.platform.domain.DataProvenance;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateClientService {
  private final TenantAccess access;
  private final PartyRepository parties;
  private final ClientCommandStore commands;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public CreateClientService(
      TenantAccess access,
      PartyRepository parties,
      ClientCommandStore commands,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.parties = parties;
    this.commands = commands;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public ClientView create(ExecutionContext context, CreateClient command) {
    access.require(context, "client:create");
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var hash = commands.requestHash(context, command);
    if (!commands.claim(ids.next(), context, command, hash, now))
      return commands.replay(context, command.metadata().commandId(), hash);
    var party =
        parties.save(
            Party.registerClient(
                command.id(),
                context.tenantId(),
                command.type(),
                command.displayName(),
                command.metadata().occurredAt(),
                DataProvenance.manual(context.actorId(), now)));
    // The JDBC role FK needs the JPA insert flushed before attaching the role.
    parties.flush();
    commands.attachClientRole(context.tenantId(), party.id());
    var result =
        new ClientView(
            party.id(),
            party.type(),
            party.displayName(),
            party.version(),
            "MANUAL",
            context.actorId(),
            now);
    audit.record(
        new AuditEvent(
            ids.next(),
            context,
            now,
            "CREATE",
            "CLIENT",
            party.id(),
            party.version(),
            null,
            null,
            "ACTIVE"));
    commands.complete(context, command.metadata().commandId(), result, clock.instant());
    return result;
  }
}
