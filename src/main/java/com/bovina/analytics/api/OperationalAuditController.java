package com.bovina.analytics.api;

import com.bovina.analytics.application.OperationalAudit;
import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-events")
public class OperationalAuditController {
  private final TenantAccess access;
  private final OperationalAudit audit;

  public OperationalAuditController(TenantAccess access, OperationalAudit audit) {
    this.access = access;
    this.audit = audit;
  }

  @GetMapping
  public PageResult<OperationalAudit.Entry> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var context =
        access.resolve(
            new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
            tenant,
            UUID.fromString((String) request.getAttribute("traceId")));
    return audit.search(context, entityType, entityId, new SearchPage(null, page, size));
  }
}
