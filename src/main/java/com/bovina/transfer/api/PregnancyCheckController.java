package com.bovina.transfer.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.transfer.application.*;
import com.bovina.transfer.domain.*;
import com.bovina.transfer.infrastructure.PregnancyCheckStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PregnancyCheckController {
  private final TenantAccess access;
  private final PregnancyChecks checks;

  public PregnancyCheckController(TenantAccess access, PregnancyChecks checks) {
    this.access = access;
    this.checks = checks;
  }

  @PostMapping("/pregnancy-checks:bulk")
  public PregnancyCheckBatch.Result record(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody BatchRequest input) {
    return checks.record(context(jwt, tenant, request), key, input.value());
  }

  @PostMapping("/pregnancy-checks/{id}:invalidate")
  public PregnancyChecks.InvalidationView invalidate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody InvalidationRequest input) {
    return checks.invalidate(
        context(jwt, tenant, request), key, id, new PregnancyChecks.Invalidate(input.reason()));
  }

  @GetMapping("/transfers/{id}/pregnancy-checks")
  public PageResult<PregnancyCheckStore.HistoryEntry> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return checks.history(context(jwt, tenant, request), id, new SearchPage("", page, size));
  }

  @GetMapping("/transfers/{id}/pregnancy-outcome")
  public PregnancyChecks.LatestOutcome latest(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return checks.latest(context(jwt, tenant, request), id);
  }

  @GetMapping("/pregnancy-follow-ups")
  public PageResult<PregnancyCheckStore.FollowUp> followUps(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam OutcomeWindows.Cohort cohort,
      @RequestParam LocalDate asOf,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return checks.followUps(
        context(jwt, tenant, request), cohort, asOf, new SearchPage("", page, size));
  }

  public record BatchRequest(
      @NotNull UUID batchId, @NotEmpty @Size(max = 200) List<@Valid ItemRequest> items) {
    PregnancyCheckBatch value() {
      return new PregnancyCheckBatch(batchId, items.stream().map(ItemRequest::value).toList());
    }
  }

  public record ItemRequest(
      @NotNull UUID itemId,
      @NotNull UUID id,
      @NotNull UUID transferId,
      @NotNull Instant checkedAt,
      @NotBlank @Size(max = 64) String timezone,
      @NotNull PregnancyCheck.Result result,
      @NotBlank @Size(max = 48) String methodCode,
      @Size(max = 2000) String observations,
      @NotNull UUID professionalId,
      UUID supersedesCheckId,
      @Size(max = 500) String correctionReason) {
    PregnancyCheckBatch.Item value() {
      return new PregnancyCheckBatch.Item(
          itemId,
          id,
          transferId,
          checkedAt,
          timezone,
          result,
          methodCode,
          observations,
          professionalId,
          supersedesCheckId,
          correctionReason);
    }
  }

  public record InvalidationRequest(@NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
