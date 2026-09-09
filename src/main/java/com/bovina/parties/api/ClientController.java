package com.bovina.parties.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.*;
import com.bovina.parties.domain.ClientType;
import com.bovina.platform.application.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {
  private final TenantAccess access;
  private final CreateClientService createClient;
  private final GetClient getClient;

  public ClientController(
      TenantAccess access, CreateClientService createClient, GetClient getClient) {
    this.access = access;
    this.createClient = createClient;
    this.getClient = getClient;
  }

  @PostMapping
  @Operation(summary = "Register a client; requires client:create and a UUIDv7 Idempotency-Key")
  public ResponseEntity<ClientView> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ClientRequest body,
      HttpServletRequest request) {
    var result =
        createClient.create(
            context(jwt, organization, request),
            new CreateClient(
                body.id(),
                body.type(),
                body.displayName(),
                new CommandMetadata(key, body.occurredAt())));
    return ResponseEntity.created(URI.create("/api/v1/clients/" + result.id()))
        .eTag(Long.toString(result.version()))
        .body(result);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a client; requires client:read")
  public ResponseEntity<ClientView> get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      @PathVariable UUID id,
      HttpServletRequest request) {
    var result = getClient.get(context(jwt, organization, request), id);
    return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
  }

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }

  public record ClientRequest(
      @NotNull UUID id,
      @NotNull ClientType type,
      @NotBlank @Size(max = 200) String displayName,
      @NotNull Instant occurredAt) {}
}
