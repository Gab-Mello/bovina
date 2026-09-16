package com.bovina.parties.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.ClientImports;
import com.bovina.parties.domain.ClientImportBatch;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/clients")
public class ClientImportController {
  private final TenantAccess access;
  private final ClientImports imports;

  public ClientImportController(TenantAccess access, ClientImports imports) {
    this.access = access;
    this.imports = imports;
  }

  @PostMapping("/imports:dry-run")
  public ClientImportBatch.Result preview(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestBody ClientImportBatch body) {
    return imports.preview(context(jwt, organization, request), body);
  }

  @PostMapping("/imports")
  public ClientImportBatch.Result commit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody ClientImportBatch body) {
    return imports.commit(context(jwt, organization, request), key, body);
  }

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
