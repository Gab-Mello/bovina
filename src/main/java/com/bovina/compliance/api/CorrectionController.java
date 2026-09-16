package com.bovina.compliance.api;

import com.bovina.compliance.application.RecordCorrections;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/corrections")
public class CorrectionController {
  private final RecordCorrections corrections;
  private final TenantAccess access;

  public CorrectionController(RecordCorrections corrections, TenantAccess access) {
    this.corrections = corrections;
    this.access = access;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RecordCorrections.View request(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody RecordCorrections.Request input) {
    return corrections.request(context(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public RecordCorrections.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return corrections.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<RecordCorrections.View> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return corrections.page(context(jwt, tenant, request), new SearchPage(null, page, size));
  }

  @GetMapping("/{id}/impact")
  public RecordCorrections.Impact impact(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return corrections.impact(context(jwt, tenant, request), id);
  }

  @PostMapping("/{id}:reject")
  public RecordCorrections.View reject(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody RecordCorrections.Rejection input) {
    return corrections.reject(context(jwt, tenant, request), key, id, input);
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
