package com.bovina.distribution.api;

import com.bovina.distribution.application.Recalls;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recalls")
public class RecallController {
  private final Recalls recalls;
  private final DistributionApiContext context;

  public RecallController(Recalls recalls, DistributionApiContext context) {
    this.recalls = recalls;
    this.context = context;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Recalls.CaseView open(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Recalls.Open input) {
    return recalls.open(context.resolve(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public Recalls.CaseView get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return recalls.get(context.resolve(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<Recalls.CaseView> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return recalls.page(context.resolve(jwt, tenant, request), new SearchPage(null, page, size));
  }

  @PostMapping("/{id}:analyze-impact")
  public Recalls.Impact analyze(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody Recalls.Analysis input) {
    return recalls.analyze(context.resolve(jwt, tenant, request), id, input);
  }

  @PostMapping("/{id}:place-holds")
  public Recalls.HoldExecution holds(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Recalls.HoldCommand input) {
    return recalls.placeHolds(context.resolve(jwt, tenant, request), key, id, input);
  }

  @GetMapping("/{id}/hold-executions/{executionId}")
  public Recalls.HoldExecution execution(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @PathVariable UUID executionId) {
    return recalls.execution(context.resolve(jwt, tenant, request), id, executionId);
  }
}
