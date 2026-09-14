package com.bovina.embryology.api;

import com.bovina.embryology.application.*;
import com.bovina.embryology.domain.*;
import com.bovina.embryology.infrastructure.EmbryologyFacts;
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
@RequestMapping("/api/v1/embryos")
public class EmbryoController {
  private final TenantAccess access;
  private final Embryos embryos;
  private final EmbryoEvaluations evaluations;

  public EmbryoController(TenantAccess access, Embryos embryos, EmbryoEvaluations evaluations) {
    this.access = access;
    this.embryos = embryos;
    this.evaluations = evaluations;
  }

  @PostMapping(":bulk")
  public EmbryoBatch.Result identify(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody BatchRequest input) {
    return embryos.identify(context(jwt, tenant, request), key, input.value());
  }

  @GetMapping("/{id}")
  public Embryos.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return embryos.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<Embryos.View> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(required = false) UUID matingId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return embryos.search(context(jwt, tenant, request), matingId, new SearchPage("", page, size));
  }

  @PostMapping("/{id}:reserve")
  public Embryos.View reserve(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody VersionRequest body) {
    return embryos.transition(
        context(jwt, tenant, request),
        key,
        id,
        body.expectedVersion(),
        Embryos.Action.RESERVE,
        null);
  }

  @PostMapping("/{id}:release-reservation")
  public Embryos.View release(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody VersionRequest body) {
    return embryos.transition(
        context(jwt, tenant, request),
        key,
        id,
        body.expectedVersion(),
        Embryos.Action.RELEASE_RESERVATION,
        null);
  }

  @PostMapping("/{id}:discard")
  public Embryos.View discard(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody DiscardRequest body) {
    return embryos.transition(
        context(jwt, tenant, request),
        key,
        id,
        body.expectedVersion(),
        Embryos.Action.DISCARD,
        body.reason());
  }

  @PostMapping("/{id}/holds")
  public EmbryologyFacts.Hold hold(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody HoldRequest body) {
    return embryos.openHold(
        context(jwt, tenant, request),
        key,
        id,
        new Embryos.OpenHold(body.id(), body.type(), body.reason()));
  }

  @PostMapping("/{id}/holds/{holdId}:release")
  public EmbryologyFacts.Hold releaseHold(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @PathVariable UUID holdId,
      @Valid @RequestBody ReasonRequest body) {
    return embryos.releaseHold(context(jwt, tenant, request), key, id, holdId, body.reason());
  }

  @GetMapping("/{id}/evaluations")
  public List<EmbryoEvaluation> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return evaluations.history(context(jwt, tenant, request), id);
  }

  public record BatchRequest(
      @NotNull UUID batchId,
      @Valid Source source,
      @NotEmpty @Size(max = 200) List<@Valid Item> items) {
    EmbryoBatch value() {
      return new EmbryoBatch(
          batchId,
          source == null ? null : source.value(),
          items.stream().map(Item::value).toList());
    }
  }

  public record Source(
      @NotNull DataProvenance.Origin origin, UUID sourceDocumentId, UUID apiClientId) {
    EmbryoBatch.Source value() {
      return new EmbryoBatch.Source(origin, sourceDocumentId, apiClientId);
    }
  }

  public record Item(
      @NotNull UUID itemId,
      @NotNull UUID id,
      @NotNull UUID matingId,
      @NotBlank @Size(max = 160) String humanCode,
      UUID ownerId,
      @NotNull Instant identifiedAt) {
    EmbryoBatch.Item value() {
      return new EmbryoBatch.Item(itemId, id, matingId, humanCode, ownerId, identifiedAt);
    }
  }

  public record VersionRequest(@PositiveOrZero long expectedVersion) {}

  public record DiscardRequest(
      @PositiveOrZero long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

  public record HoldRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 48) String type,
      @NotBlank @Size(max = 500) String reason) {}

  public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
