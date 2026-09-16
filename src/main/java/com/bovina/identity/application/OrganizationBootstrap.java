package com.bovina.identity.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.identity.domain.*;
import com.bovina.identity.infrastructure.BootstrapProperties;
import com.bovina.identity.infrastructure.IdentityStore;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationBootstrap {
  private final IdentityStore identities;
  private final BootstrapProperties bootstrap;
  private final StableIds ids;
  private final Clock clock;
  private final AuditRecorder audit;

  public OrganizationBootstrap(
      IdentityStore identities,
      BootstrapProperties bootstrap,
      StableIds ids,
      Clock clock,
      AuditRecorder audit) {
    this.identities = identities;
    this.bootstrap = bootstrap;
    this.ids = ids;
    this.clock = clock;
    this.audit = audit;
  }

  @Transactional
  public UUID create(
      AuthenticatedIdentity identity, UUID correlationId, CreateOrganization command) {
    if (!bootstrap.allows(identity.issuer(), identity.subject()))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.FORBIDDEN, "BOOTSTRAP_DISABLED", "Bootstrap access denied");
    var now = clock.instant();
    var actor = identities.registerUser(ids.next(), identity, now);
    var organization =
        new Organization(
            command.id(),
            command.legalName(),
            command.tradeName(),
            command.taxId(),
            command.timezone(),
            actor,
            now);
    var membership =
        new OrganizationMembership(
            ids.next(), organization.id(), actor, MembershipRole.ORG_ADMIN, now, null);
    identities.createOrganization(organization, membership);
    var context =
        new ExecutionContext(
            organization.id(), actor, MembershipRole.ORG_ADMIN.permissions(), correlationId);
    audit.record(
        new AuditEvent(
            ids.next(),
            context,
            now,
            "CREATE",
            "ORGANIZATION",
            organization.id(),
            0L,
            null,
            null,
            "ACTIVE"));
    audit.record(
        new AuditEvent(
            ids.next(),
            context,
            now,
            "CREATE",
            "MEMBERSHIP",
            membership.id(),
            0L,
            null,
            null,
            "ORG_ADMIN"));
    return organization.id();
  }

  public record CreateOrganization(
      UUID id, String legalName, String tradeName, String taxId, ZoneId timezone) {}
}
