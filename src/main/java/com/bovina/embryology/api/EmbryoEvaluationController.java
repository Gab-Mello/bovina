package com.bovina.embryology.api;

import com.bovina.embryology.application.EmbryoEvaluations;
import com.bovina.embryology.domain.EvaluationBatch;
import com.bovina.identity.application.*;
import com.bovina.platform.application.ExecutionContext;
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
@RequestMapping("/api/v1/embryo-evaluations")
public class EmbryoEvaluationController {
  private final TenantAccess access;
  private final EmbryoEvaluations evaluations;

  public EmbryoEvaluationController(TenantAccess access, EmbryoEvaluations evaluations) {
    this.access = access;
    this.evaluations = evaluations;
  }

  @PostMapping(":bulk")
  public EvaluationBatch.Result record(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody BatchRequest input) {
    return evaluations.record(context(jwt, tenant, request), key, input.value());
  }

  public record BatchRequest(
      @NotNull UUID batchId,
      @Valid Source source,
      @NotEmpty @Size(max = 200) List<@Valid Item> items) {
    EvaluationBatch value() {
      return new EvaluationBatch(
          batchId,
          source == null ? null : source.value(),
          items.stream().map(Item::value).toList());
    }
  }

  public record Source(
      @NotNull DataProvenance.Origin origin, UUID sourceDocumentId, UUID apiClientId) {
    EvaluationBatch.Source value() {
      return new EvaluationBatch.Source(origin, sourceDocumentId, apiClientId);
    }
  }

  public record Item(
      @NotNull UUID itemId,
      @NotNull UUID id,
      @NotNull UUID embryoId,
      @NotNull UUID schemeVersionId,
      @NotNull UUID developmentStageCodeId,
      @NotNull UUID qualityGradeCodeId,
      @NotNull Instant evaluatedAt,
      UUID evaluatorProfessionalId,
      @Size(max = 2000) String notes,
      UUID supersedesEvaluationId) {
    EvaluationBatch.Item value() {
      return new EvaluationBatch.Item(
          itemId,
          id,
          embryoId,
          schemeVersionId,
          developmentStageCodeId,
          qualityGradeCodeId,
          evaluatedAt,
          evaluatorProfessionalId,
          notes,
          supersedesEvaluationId);
    }
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
