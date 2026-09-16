package com.bovina.embryology.api;

import com.bovina.embryology.application.AssessmentSchemes;
import com.bovina.embryology.domain.AssessmentCatalog.*;
import com.bovina.identity.application.*;
import com.bovina.platform.application.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessment-schemes")
public class AssessmentSchemeController {
  private final TenantAccess access;
  private final AssessmentSchemes schemes;

  public AssessmentSchemeController(TenantAccess access, AssessmentSchemes schemes) {
    this.access = access;
    this.schemes = schemes;
  }

  @PostMapping
  public ResponseEntity<Scheme> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody SchemeRequest input) {
    var result =
        schemes.register(
            context(jwt, tenant, request),
            key,
            new Scheme(input.id(), input.code(), input.name(), "ACTIVE"));
    return ResponseEntity.created(URI.create("/api/v1/assessment-schemes/" + result.id()))
        .body(result);
  }

  @PostMapping("/{schemeId}/versions")
  public ResponseEntity<Version> publish(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID schemeId,
      @Valid @RequestBody VersionRequest input) {
    var result =
        schemes.publish(
            context(jwt, tenant, request),
            key,
            new AssessmentSchemes.Publish(
                input.id(),
                schemeId,
                input.versionLabel(),
                input.effectiveFrom(),
                input.codes().stream()
                    .map(
                        c ->
                            new AssessmentSchemes.CodeInput(
                                c.id(), c.dimension(), c.code(), c.displayName(), c.sortOrder()))
                    .toList()));
    return ResponseEntity.created(URI.create("/api/v1/assessment-schemes/versions/" + result.id()))
        .body(result);
  }

  @GetMapping("/versions/{id}")
  public Version getVersion(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return schemes.getVersion(context(jwt, tenant, request), id);
  }

  public record SchemeRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 80) String code,
      @NotBlank @Size(max = 200) String name) {}

  public record VersionRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 80) String versionLabel,
      LocalDate effectiveFrom,
      @NotEmpty @Size(max = 200) List<@Valid CodeRequest> codes) {}

  public record CodeRequest(
      @NotNull UUID id,
      @NotNull Dimension dimension,
      @NotBlank @Size(max = 80) String code,
      @NotBlank @Size(max = 200) String displayName,
      int sortOrder) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
