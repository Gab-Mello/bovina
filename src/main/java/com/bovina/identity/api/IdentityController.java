package com.bovina.identity.api;

import com.bovina.identity.application.*;
import com.bovina.identity.domain.MembershipRole;
import com.bovina.platform.application.ExecutionContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
  private final CurrentIdentity current;
  private final TenantAccess access;
  private final OrganizationBootstrap bootstrap;
  private final MembershipAdministration memberships;

  public IdentityController(
      CurrentIdentity current,
      TenantAccess access,
      OrganizationBootstrap bootstrap,
      MembershipAdministration memberships) {
    this.current = current;
    this.access = access;
    this.bootstrap = bootstrap;
    this.memberships = memberships;
  }

  @GetMapping("/me")
  public ExecutionContext me(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request) {
    return current.context(jwt, organization, request);
  }

  @GetMapping("/me/memberships")
  public List<MembershipView> memberships(@AuthenticationPrincipal Jwt jwt) {
    return access.memberships(current.identity(jwt));
  }

  @PostMapping("/bootstrap/organizations")
  @Operation(summary = "Provision an organization as the explicitly configured bootstrap operator")
  public ResponseEntity<CreatedOrganization> bootstrap(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody OrganizationRequest body,
      HttpServletRequest request) {
    var id =
        bootstrap.create(
            current.identity(jwt),
            UUID.fromString((String) request.getAttribute("traceId")),
            new OrganizationBootstrap.CreateOrganization(
                body.id(), body.legalName(), body.tradeName(), body.taxId(), body.timezone()));
    return ResponseEntity.status(201).body(new CreatedOrganization(id));
  }

  @PostMapping("/memberships")
  @Operation(summary = "Grant membership; requires membership:manage")
  public ResponseEntity<CreatedMembership> grant(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      @Valid @RequestBody MembershipRequest body,
      HttpServletRequest request) {
    var id =
        memberships.grant(
            current.context(jwt, organization, request),
            new MembershipAdministration.GrantMembership(
                body.id(),
                new AuthenticatedIdentity(jwt.getIssuer().toString(), body.subject()),
                body.role(),
                body.validUntil()));
    return ResponseEntity.created(URI.create("/api/v1/memberships/" + id))
        .body(new CreatedMembership(id, 0));
  }

  @PostMapping("/memberships/{id}:revoke")
  public ResponseEntity<Void> revoke(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      @PathVariable UUID id,
      @Valid @RequestBody RevokeRequest body,
      HttpServletRequest request) {
    memberships.revoke(
        current.context(jwt, organization, request), id, body.expectedVersion(), body.reason());
    return ResponseEntity.noContent().build();
  }

  public record OrganizationRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 200) String legalName,
      @Size(max = 200) String tradeName,
      @NotBlank @Size(max = 32) String taxId,
      @NotNull ZoneId timezone) {}

  public record MembershipRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 255) String subject,
      @NotNull MembershipRole role,
      Instant validUntil) {}

  public record RevokeRequest(
      @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

  public record CreatedOrganization(UUID id) {}

  public record CreatedMembership(UUID id, long version) {}
}
