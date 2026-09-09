package com.bovina.operations.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.ResponsibleTechnicians;
import com.bovina.operations.domain.ResponsibleTechnicianAssignment;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/establishments/{establishment}/responsible-technicians")
public class ResponsibleTechnicianController {
  private final TenantAccess access;
  private final ResponsibleTechnicians technicians;

  public ResponsibleTechnicianController(TenantAccess access, ResponsibleTechnicians technicians) {
    this.access = access;
    this.technicians = technicians;
  }

  @PostMapping
  public ResponseEntity<ResponsibleTechnicianAssignment> assign(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody ResponsibleTechnicians.Assign body) {
    return ResponseEntity.status(201)
        .body(technicians.assign(context(jwt, organization, request), key, establishment, body));
  }

  @GetMapping
  public PageResult<ResponsibleTechnicianAssignment> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return technicians.history(
        context(jwt, organization, request), establishment, new SearchPage("", page, size));
  }

  @PostMapping("/{id}:end")
  public ResponsibleTechnicianAssignment end(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody EndRequest body) {
    return technicians.end(
        context(jwt, organization, request),
        key,
        establishment,
        id,
        new ResponsibleTechnicians.End(body.expectedVersion(), body.until()));
  }

  public record EndRequest(
      @NotNull @PositiveOrZero Long expectedVersion, @NotNull LocalDate until) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
