package com.bovina.semen.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.semen.application.SemenBatches;
import com.bovina.semen.domain.ExternalEstablishmentReference;
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
@RequestMapping("/api/v1/external-establishments")
public class ExternalEstablishmentController {
  private final TenantAccess access;
  private final SemenBatches batches;

  public ExternalEstablishmentController(TenantAccess access, SemenBatches batches) {
    this.access = access;
    this.batches = batches;
  }

  @PostMapping
  public ResponseEntity<ExternalEstablishmentReference> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Registration input) {
    var result =
        batches.registerProducer(
            context(jwt, tenant, request),
            key,
            new ExternalEstablishmentReference(
                input.id(),
                input.legalPartyId(),
                input.name(),
                input.establishmentType(),
                input.registrationNumber(),
                input.registrationAuthority(),
                input.country(),
                input.verificationStatus(),
                input.verificationDocumentId(),
                "ACTIVE",
                0));
    return ResponseEntity.created(URI.create("/api/v1/external-establishments/" + result.id()))
        .body(result);
  }

  @GetMapping("/{id}")
  public ExternalEstablishmentReference get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return batches.getProducer(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<ExternalEstablishmentReference> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return batches.producers(context(jwt, tenant, request), new SearchPage("", page, size));
  }

  public record Registration(
      @NotNull UUID id,
      UUID legalPartyId,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 48) String establishmentType,
      @Size(max = 120) String registrationNumber,
      @Size(max = 160) String registrationAuthority,
      @NotBlank @Size(min = 2, max = 2) String country,
      @NotBlank @Size(max = 48) String verificationStatus,
      UUID verificationDocumentId) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
