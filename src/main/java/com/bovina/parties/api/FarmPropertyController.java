package com.bovina.parties.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.FarmProperties;
import com.bovina.parties.domain.FarmProperty;
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
@RequestMapping("/api/v1/farm-properties")
public class FarmPropertyController {
  private final TenantAccess access;
  private final FarmProperties properties;

  public FarmPropertyController(TenantAccess access, FarmProperties properties) {
    this.access = access;
    this.properties = properties;
  }

  @PostMapping
  public ResponseEntity<FarmProperties.View> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody FarmProperty.Details body) {
    var result = properties.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/farm-properties/" + result.details().id()))
        .body(result);
  }

  @GetMapping("/{id}")
  public FarmProperties.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return properties.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<FarmProperties.View> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return properties.search(context(jwt, organization, request), new SearchPage(q, page, size));
  }

  @PostMapping("/{id}:archive")
  public FarmProperties.View archive(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ArchiveRequest body) {
    return properties.archive(context(jwt, organization, request), key, id, body.expectedVersion());
  }

  public record ArchiveRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
