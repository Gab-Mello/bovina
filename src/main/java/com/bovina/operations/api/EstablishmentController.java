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
@RequestMapping("/api/v1/establishments")
public class EstablishmentController {
  private final TenantAccess access;
  private final Establishments establishments;

  public EstablishmentController(TenantAccess access, Establishments establishments) {
    this.access = access;
    this.establishments = establishments;
  }

  @PostMapping
  public ResponseEntity<Establishments.View> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Establishment.Details body) {
    var result = establishments.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/establishments/" + result.details().id()))
        .body(result);
  }

  @GetMapping("/{id}")
  public Establishments.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return establishments.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<Establishments.View> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return establishments.search(
        context(jwt, organization, request), new SearchPage(q, page, size));
  }

  @PostMapping("/{id}:deactivate")
  public Establishments.View deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody VersionRequest body) {
    return establishments.deactivate(
        context(jwt, organization, request), key, id, body.expectedVersion());
  }

  @PostMapping("/{establishment}/operational-locations")
  public ResponseEntity<OperationalLocation> registerLocation(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Establishments.RegisterLocation body) {
    var result =
        establishments.registerLocation(
            context(jwt, organization, request), key, establishment, body);
    return ResponseEntity.created(
            URI.create(
                "/api/v1/establishments/"
                    + establishment
                    + "/operational-locations/"
                    + result.id()))
        .body(result);
  }

  @GetMapping("/{establishment}/operational-locations/{id}")
  public OperationalLocation getLocation(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @PathVariable UUID id) {
    return establishments.getLocation(context(jwt, organization, request), establishment, id);
  }

  @GetMapping("/{establishment}/operational-locations")
  public PageResult<OperationalLocation> locations(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return establishments.locations(
        context(jwt, organization, request), establishment, new SearchPage(q, page, size));
  }

  @PostMapping("/{establishment}/operational-locations/{id}:deactivate")
  public OperationalLocation deactivateLocation(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID establishment,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody VersionRequest body) {
    return establishments.deactivateLocation(
        context(jwt, organization, request), key, establishment, id, body.expectedVersion());
  }

  public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
