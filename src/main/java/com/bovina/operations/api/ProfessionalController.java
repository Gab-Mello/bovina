package com.bovina.operations.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.*;
import com.bovina.operations.domain.*;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/professionals")
public class ProfessionalController {
  private final TenantAccess access;
  private final Professionals professionals;

  public ProfessionalController(TenantAccess access, Professionals professionals) {
    this.access = access;
    this.professionals = professionals;
  }

  @PostMapping
  public ResponseEntity<Professional> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Professionals.Register body) {
    var result = professionals.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/professionals/" + result.id())).body(result);
  }

  @GetMapping("/{id}")
  public Professional get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return professionals.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<Professional> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return professionals.search(context(jwt, organization, request), new SearchPage(q, page, size));
  }

  @PostMapping("/{id}:deactivate")
  public Professional deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody VersionRequest body) {
    return professionals.deactivate(
        context(jwt, organization, request), key, id, body.expectedVersion());
  }

  @PostMapping("/{id}/credentials")
  public ResponseEntity<ProfessionalCredential> credential(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Professionals.Credential body) {
    var result = professionals.addCredential(context(jwt, organization, request), key, id, body);
    return ResponseEntity.status(201).body(result);
  }

  @GetMapping("/{id}/credentials")
  public PageResult<ProfessionalCredential> credentials(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return professionals.credentials(
        context(jwt, organization, request), id, new SearchPage("", page, size));
  }

  public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
