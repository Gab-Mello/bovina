package com.bovina.fertilization.api;

import com.bovina.fertilization.application.Matings;
import com.bovina.fertilization.domain.*;
import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/matings")
public class MatingController {
  private final TenantAccess access;
  private final Matings matings;

  public MatingController(TenantAccess access, Matings matings) {
    this.access = access;
    this.matings = matings;
  }

  @PostMapping(":bulk")
  public MatingBatch.Result allocate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody BatchRequest input) {
    return matings.allocate(context(jwt, tenant, request), key, input.batch());
  }

  @GetMapping("/{id}")
  public Matings.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return matings.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<Mating> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(required = false) UUID collectionId,
      @RequestParam(required = false) UUID semenBatchId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return matings.search(
        context(jwt, tenant, request), collectionId, semenBatchId, new SearchPage("", page, size));
  }

  @PostMapping("/{id}/corrections")
  public Matings.CorrectionView requestCorrection(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody CorrectionRequest input) {
    return matings.requestCorrection(
        context(jwt, tenant, request),
        key,
        id,
        new Matings.Correction(
            input.proposedSemenBatchId(), input.proposedFertilizedAt(), input.reason()));
  }

  public record BatchRequest(
      @NotNull UUID batchId,
      @Valid Source source,
      @NotEmpty @Size(max = 100) List<@Valid Item> items) {
    MatingBatch batch() {
      return new MatingBatch(
          batchId,
          source == null ? null : source.value(),
          items.stream().map(Item::value).toList());
    }
  }

  public record Source(
      @NotNull DataProvenance.Origin origin, UUID sourceDocumentId, UUID apiClientId) {
    MatingBatch.Source value() {
      return new MatingBatch.Source(origin, sourceDocumentId, apiClientId);
    }
  }

  public record Item(
      @NotNull UUID itemId,
      @NotNull UUID id,
      @NotNull UUID collectionId,
      @NotNull UUID semenBatchId,
      @NotNull @Positive Integer allocatedOocytes,
      @NotNull Instant fertilizedAt,
      @NotBlank @Size(max = 48) String method,
      UUID responsibleProfessionalId) {
    MatingBatch.Item value() {
      return new MatingBatch.Item(
          itemId,
          id,
          collectionId,
          semenBatchId,
          allocatedOocytes,
          fertilizedAt,
          method,
          responsibleProfessionalId);
    }
  }

  public record CorrectionRequest(
      UUID proposedSemenBatchId,
      Instant proposedFertilizedAt,
      @NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
