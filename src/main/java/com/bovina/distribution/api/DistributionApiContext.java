package com.bovina.distribution.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class DistributionApiContext {
  private final TenantAccess access;

  public DistributionApiContext(TenantAccess access) {
    this.access = access;
  }

  public ExecutionContext resolve(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
