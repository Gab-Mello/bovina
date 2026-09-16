package com.bovina.embryology.api;

import com.bovina.embryology.application.CompleteMating;
import com.bovina.embryology.infrastructure.EmbryologyFacts;
import com.bovina.identity.application.*;
import com.bovina.platform.application.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/matings")
public class MatingCompletionController {
  private final TenantAccess access;
  private final CompleteMating completion;

  public MatingCompletionController(TenantAccess access, CompleteMating completion) {
    this.access = access;
    this.completion = completion;
  }

  @PostMapping("/{id}:complete-embryology")
  public CompleteMating.Result complete(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody CompletionRequest input) {
    return completion.complete(
        context(jwt, tenant, request),
        key,
        id,
        new CompleteMating.Command(
            input.expectedVersion(),
            input.producedCount(),
            input.dispositions().stream()
                .map(
                    d ->
                        new EmbryologyFacts.Disposition(d.id(), d.code(), d.quantity(), d.reason()))
                .toList()));
  }

  public record CompletionRequest(
      @PositiveOrZero long expectedVersion,
      @PositiveOrZero int producedCount,
      @NotNull @Size(max = 100) List<@Valid DispositionRequest> dispositions) {}

  public record DispositionRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 48) String code,
      @Positive int quantity,
      @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
