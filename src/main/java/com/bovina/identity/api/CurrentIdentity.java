package com.bovina.identity.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentIdentity {
  private final TenantAccess access;

  public CurrentIdentity(TenantAccess access) {
    this.access = access;
  }

  public AuthenticatedIdentity identity(Jwt jwt) {
    return new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject());
  }

  public ExecutionContext context(Jwt jwt, UUID selectedTenant, HttpServletRequest request) {
    return access.resolve(
        identity(jwt), selectedTenant, UUID.fromString((String) request.getAttribute("traceId")));
  }
}
