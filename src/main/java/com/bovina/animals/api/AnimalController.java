package com.bovina.animals.api;

import com.bovina.animals.application.Animals;
import com.bovina.animals.domain.Animal;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
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
@RequestMapping("/api/v1/animals")
public class AnimalController {
  private final TenantAccess access;
  private final Animals animals;

  public AnimalController(TenantAccess access, Animals animals) {
    this.access = access;
    this.animals = animals;
  }

  @PostMapping
  public ResponseEntity<Animals.View> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Animal.Registration body) {
    var result = animals.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/animals/" + result.registration().id()))
        .eTag(Long.toString(result.version()))
        .body(result);
  }

  @GetMapping("/{id}")
  public Animals.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return animals.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<Animals.View> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) LocalDate ownedOn) {
    return animals.search(
        context(jwt, organization, request), new SearchPage(q, page, size), ownerId, ownedOn);
  }

  @PostMapping("/{id}:archive")
  public Animals.View archive(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody VersionRequest body) {
    return animals.archive(context(jwt, organization, request), key, id, body.expectedVersion());
  }

  public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
