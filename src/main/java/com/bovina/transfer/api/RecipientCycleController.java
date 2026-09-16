package com.bovina.transfer.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.transfer.application.RecipientCycles;
import com.bovina.transfer.domain.RecipientCycle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recipient-cycles")
public class RecipientCycleController {
  private final TenantAccess access;
  private final RecipientCycles cycles;

  public RecipientCycleController(TenantAccess access, RecipientCycles cycles) {
    this.access = access;
    this.cycles = cycles;
  }

  @PostMapping
  public ResponseEntity<RecipientCycles.View> open(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody OpenRequest input) {
    var result = cycles.open(context(jwt, tenant, request), key, input.value());
    return ResponseEntity.created(URI.create("/api/v1/recipient-cycles/" + result.id()))
        .body(result);
  }

  @GetMapping("/{id}")
  public RecipientCycles.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return cycles.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<RecipientCycles.View> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(required = false) UUID recipientId,
      @RequestParam(required = false) RecipientCycle.Status status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return cycles.search(
        context(jwt, tenant, request), recipientId, status, new SearchPage("", page, size));
  }

  @PostMapping("/{id}:close")
  public RecipientCycles.View close(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody CloseRequest input) {
    return cycles.close(
        context(jwt, tenant, request),
        key,
        id,
        new RecipientCycles.Close(input.expectedVersion(), input.closedOn(), input.reason()));
  }

  public record OpenRequest(
      @NotNull UUID id,
      @NotNull UUID recipientAnimalId,
      UUID protocolVersionId,
      @NotNull LocalDate openedOn,
      @Size(max = 1000) String notes) {
    RecipientCycle.Registration value() {
      return new RecipientCycle.Registration(
          id, recipientAnimalId, protocolVersionId, openedOn, notes);
    }
  }

  public record CloseRequest(
      @PositiveOrZero long expectedVersion,
      @NotNull LocalDate closedOn,
      @NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
