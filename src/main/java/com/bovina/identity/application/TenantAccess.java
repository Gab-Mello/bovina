package com.bovina.identity.application;

import com.bovina.identity.infrastructure.IdentityStore;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantAccess {
  private final IdentityStore identities;
  private final Clock clock;

  public TenantAccess(IdentityStore identities, Clock clock) {
    this.identities = identities;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ExecutionContext resolve(
      AuthenticatedIdentity identity, UUID selectedTenant, UUID correlationId) {
    var memberships = identities.memberships(identity, selectedTenant, clock.instant(), 2);
    if (memberships.isEmpty()) throw denied();
    if (memberships.size() != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "TENANT_SELECTION_REQUIRED",
          "Select one of your organizations");
    var membership = memberships.getFirst();
    return new ExecutionContext(
        membership.organizationId(), membership.actorId(), membership.permissions(), correlationId);
  }

  @Transactional(readOnly = true)
  public List<MembershipView> memberships(AuthenticatedIdentity identity) {
    return identities.memberships(identity, null, clock.instant(), 100);
  }

  // No permission cache: revocation affects the next authorization check, even with an unexpired
  // JWT.
  public void require(ExecutionContext context, String permission) {
    if (!context.permissions().contains(permission)
        || !identities.allows(context.tenantId(), context.actorId(), permission, clock.instant()))
      throw denied();
  }

  private static ApplicationFailure denied() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.FORBIDDEN, "ACCESS_DENIED", "Access denied");
  }
}
