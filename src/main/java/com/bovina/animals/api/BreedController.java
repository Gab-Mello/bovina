package com.bovina.animals.api;

import com.bovina.animals.application.Breeds;
import com.bovina.animals.domain.Breed;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
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
@RequestMapping("/api/v1/breeds")
public class BreedController {
  private final TenantAccess access;
  private final Breeds breeds;

  public BreedController(TenantAccess access, Breeds breeds) {
    this.access = access;
    this.breeds = breeds;
  }

  @PostMapping
  public ResponseEntity<Breed> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Breeds.Register body) {
    var result = breeds.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/breeds/" + result.id())).body(result);
  }

  @GetMapping("/{id}")
  public Breed get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return breeds.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<Breed> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return breeds.search(context(jwt, organization, request), new SearchPage(q, page, size));
  }

  @PostMapping("/{id}:deactivate")
  public Breed deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody VersionRequest body) {
    return breeds.deactivate(context(jwt, organization, request), key, id, body.expectedVersion());
  }

  public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
