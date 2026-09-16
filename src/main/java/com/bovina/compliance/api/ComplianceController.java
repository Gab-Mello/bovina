package com.bovina.compliance.api;

import com.bovina.compliance.application.ComplianceEvaluations;
import com.bovina.compliance.domain.ComplianceRuleDefinition;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {
  private final ComplianceEvaluations evaluations;
  private final TenantAccess access;

  public ComplianceController(ComplianceEvaluations evaluations, TenantAccess access) {
    this.evaluations = evaluations;
    this.access = access;
  }

  @PostMapping("/evaluations:preview")
  public ComplianceEvaluations.Evaluation preview(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestBody ComplianceEvaluations.Preview input) {
    return evaluations.preview(context(jwt, tenant, request), input);
  }

  @GetMapping("/rules")
  public PageResult<ComplianceRuleDefinition> rules(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return evaluations.definitions(context(jwt, tenant, request), new SearchPage(null, page, size));
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
