package com.bovina.animals.api;

import com.bovina.animals.application.*;
import com.bovina.animals.domain.*;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
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
@RequestMapping("/api/v1/animals/{animal}")
public class AnimalHistoryController {
  private final TenantAccess access;
  private final AnimalIdentifiers identifiers;
  private final AnimalOwnership ownership;

  public AnimalHistoryController(
      TenantAccess access, AnimalIdentifiers identifiers, AnimalOwnership ownership) {
    this.access = access;
    this.identifiers = identifiers;
    this.ownership = ownership;
  }

  @PostMapping("/identifiers")
  public ResponseEntity<AnimalIdentifier> addIdentifier(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID animal,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody AnimalIdentifiers.Add body) {
    return ResponseEntity.status(201)
        .body(identifiers.add(context(jwt, organization, request), key, animal, body));
  }

  @GetMapping("/identifiers")
  public PageResult<AnimalIdentifier> identifiers(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID animal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return identifiers.history(
        context(jwt, organization, request), animal, new SearchPage("", page, size));
  }

  @PostMapping("/identifiers/{id}:retire")
  public AnimalIdentifier retire(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID animal,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody RetireRequest body) {
    return identifiers.retire(
        context(jwt, organization, request),
        key,
        animal,
        id,
        new AnimalIdentifiers.Retire(body.expectedVersion(), body.disposition(), body.reason()));
  }

  @PostMapping("/ownership")
  public ResponseEntity<AnimalOwnershipAssignment> assignOwner(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID animal,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody AnimalOwnership.Assign body) {
    return ResponseEntity.status(201)
        .body(ownership.assign(context(jwt, organization, request), key, animal, body));
  }

  @GetMapping("/ownership")
  public PageResult<AnimalOwnershipAssignment> ownership(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID animal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ownership.history(
        context(jwt, organization, request), animal, new SearchPage("", page, size));
  }

  @PostMapping("/ownership/{id}:end")
  public AnimalOwnershipAssignment endOwnership(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID animal,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody EndRequest body) {
    return ownership.end(
        context(jwt, organization, request),
        key,
        animal,
        id,
        new AnimalOwnership.End(body.expectedVersion(), body.until()));
  }

  public record RetireRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotNull AnimalIdentifier.Status disposition,
      @NotBlank @Size(max = 500) String reason) {}

  public record EndRequest(
      @NotNull @PositiveOrZero Long expectedVersion, @NotNull LocalDate until) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
