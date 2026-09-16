package com.bovina.opu.api;

import com.bovina.animals.application.DonorDirectory.Donor;
import com.bovina.identity.application.*;
import com.bovina.opu.application.OocyteCollections;
import com.bovina.opu.domain.OocyteCounts;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/oocyte-collections")
public class OocyteCollectionController {
  private final TenantAccess access;
  private final OocyteCollections collections;

  public OocyteCollectionController(TenantAccess access, OocyteCollections collections) {
    this.access = access;
    this.collections = collections;
  }

  @GetMapping("/{id}")
  public OocyteCollections.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return collections.get(context(jwt, tenant, request), id);
  }

  @GetMapping("/{id}/donor-snapshot")
  public Donor snapshot(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return collections.identitySnapshot(context(jwt, tenant, request), id);
  }

  @PostMapping("/{id}:correct")
  public OocyteCollections.View correct(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody CorrectionRequest input) {
    return collections.correct(
        context(jwt, tenant, request),
        key,
        id,
        new OocyteCollections.Correction(
            input.expectedVersion(),
            new OocyteCounts(
                input.counts().totalRecovered(),
                input.counts().viable(),
                input.counts().folliclesAspirated()),
            input.notes(),
            input.reason()));
  }

  public record Counts(
      @NotNull @PositiveOrZero Integer totalRecovered,
      @NotNull @PositiveOrZero Integer viable,
      @PositiveOrZero Integer folliclesAspirated) {}

  public record CorrectionRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotNull @Valid Counts counts,
      @Size(max = 2000) String notes,
      @NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
