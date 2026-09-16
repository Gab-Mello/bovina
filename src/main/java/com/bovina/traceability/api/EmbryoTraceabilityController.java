package com.bovina.traceability.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.traceability.application.EmbryoTraceability;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/embryos/{id}")
public class EmbryoTraceabilityController {
  private final TenantAccess access;
  private final EmbryoTraceability traceability;

  public EmbryoTraceabilityController(TenantAccess access, EmbryoTraceability traceability) {
    this.access = access;
    this.traceability = traceability;
  }

  @GetMapping("/traceability")
  public EmbryoTraceability.Lineage lineage(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return traceability.lineage(context(jwt, tenant, request), id);
  }

  @GetMapping("/timeline")
  public PageResult<EmbryoTraceability.TimelineEntry> timeline(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return traceability.timeline(
        context(jwt, tenant, request), id, new SearchPage(null, page, size));
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
