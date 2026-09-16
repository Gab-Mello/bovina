package com.bovina.protocols.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import com.bovina.protocols.application.ProtocolWithdrawal;
import com.bovina.protocols.application.Protocols;
import com.bovina.protocols.domain.*;
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
@RequestMapping("/api/v1/protocol-definitions")
public class ProtocolController {
  private final TenantAccess access;
  private final Protocols protocols;

  public ProtocolController(TenantAccess access, Protocols protocols) {
    this.access = access;
    this.protocols = protocols;
  }

  @PostMapping
  public ResponseEntity<ProtocolDefinition> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Protocols.Register body) {
    var result = protocols.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/protocol-definitions/" + result.id()))
        .body(result);
  }

  @GetMapping("/{id}")
  public ProtocolDefinition get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return protocols.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<ProtocolDefinition> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return protocols.search(context(jwt, organization, request), new SearchPage(q, page, size));
  }

  @PostMapping("/{id}:deactivate")
  public ProtocolDefinition deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody VersionRequest body) {
    return protocols.deactivate(
        context(jwt, organization, request), key, id, body.expectedVersion());
  }

  @PostMapping("/{definition}/versions")
  public ResponseEntity<ProtocolVersion> publish(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID definition,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Protocols.Publish body) {
    var result = protocols.publish(context(jwt, organization, request), key, definition, body);
    return ResponseEntity.created(
            URI.create("/api/v1/protocol-definitions/" + definition + "/versions/" + result.id()))
        .body(result);
  }

  @GetMapping("/{definition}/versions/{id}")
  public ProtocolVersion version(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID definition,
      @PathVariable UUID id) {
    return protocols.version(context(jwt, organization, request), definition, id);
  }

  @GetMapping("/{definition}/versions")
  public PageResult<ProtocolVersion> versions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID definition,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return protocols.versions(
        context(jwt, organization, request), definition, new SearchPage("", page, size));
  }

  public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  @PostMapping("/{definition}/versions/{id}:deactivate")
  public ProtocolWithdrawal withdraw(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID definition,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody WithdrawalRequest body) {
    return protocols.withdraw(
        context(jwt, organization, request), key, definition, id, body.reason());
  }

  @GetMapping("/{definition}/versions/{id}/withdrawal")
  public ProtocolWithdrawal withdrawal(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID definition,
      @PathVariable UUID id) {
    return protocols.withdrawal(context(jwt, organization, request), definition, id);
  }

  public record WithdrawalRequest(@NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
