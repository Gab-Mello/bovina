package com.bovina.identity.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.identity.domain.MembershipRole;
import com.bovina.identity.domain.OrganizationMembership;
import com.bovina.identity.infrastructure.IdentityStore;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipAdministration {
  private final TenantAccess access;
  private final IdentityStore identities;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public MembershipAdministration(
      TenantAccess access,
      IdentityStore identities,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.identities = identities;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public UUID grant(ExecutionContext context, GrantMembership command) {
    access.require(context, "membership:manage");
    var now = clock.instant();
    if (command.validUntil() != null && !command.validUntil().isAfter(now)) {
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_MEMBERSHIP_INTERVAL",
          "Membership expiry must follow its start");
    }
    var user = identities.registerUser(ids.next(), command.identity(), now);
    var membership =
        new OrganizationMembership(
            command.id(), context.tenantId(), user, command.role(), now, command.validUntil());
    identities.createMembership(membership);
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
            command.role().name()));
    return membership.id();
  }

  @Transactional
  public void revoke(
      ExecutionContext context, UUID membershipId, long expectedVersion, String reason) {
    access.require(context, "membership:manage");
    var membership =
        identities
            .membership(context.tenantId(), membershipId)
            .orElseThrow(
                () ->
                    new ApplicationFailure(
                        ApplicationFailure.Kind.NOT_FOUND,
                        "MEMBERSHIP_NOT_FOUND",
                        "Membership not found"));
    if (membership.version() != expectedVersion)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "STALE_VERSION", "Membership version has changed");
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED, "REASON_REQUIRED", "A reason is required");
    var previous = membership.status().name();
    membership.revoke();
    identities.flush();
    audit.record(
        new AuditEvent(
            ids.next(),
            context,
            clock.instant(),
            "REVOKE",
            "MEMBERSHIP",
            membership.id(),
            membership.version(),
            reason,
            previous,
            "REVOKED"));
  }

  public record GrantMembership(
      UUID id, AuthenticatedIdentity identity, MembershipRole role, Instant validUntil) {}
}
