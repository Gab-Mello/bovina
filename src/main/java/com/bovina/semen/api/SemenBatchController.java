package com.bovina.semen.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.semen.application.SemenBatches;
import com.bovina.semen.domain.SemenBatch;
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
@RequestMapping("/api/v1/semen-batches")
public class SemenBatchController {
  private final TenantAccess access;
  private final SemenBatches batches;

  public SemenBatchController(TenantAccess access, SemenBatches batches) {
    this.access = access;
    this.batches = batches;
  }

  @PostMapping
  public ResponseEntity<SemenBatch> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Registration input) {
    var result =
        batches.registerBatch(
            context(jwt, tenant, request),
            key,
            new SemenBatches.Registration(
                input.id(),
                input.batchCode(),
                input.sireId(),
                input.producerEstablishmentId(),
                input.provenanceCode(),
                input.verificationStatus(),
                input.semenType(),
                input.ownerId(),
                input.receivedAt(),
                input.sourceDocumentId()));
    return ResponseEntity.created(URI.create("/api/v1/semen-batches/" + result.id())).body(result);
  }

  @GetMapping("/{id}")
  public SemenBatch get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return batches.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<SemenBatch> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return batches.batches(context(jwt, tenant, request), new SearchPage(q, page, size));
  }

  public record Registration(
      @NotNull UUID id,
      @NotBlank @Size(max = 160) String batchCode,
      @NotNull UUID sireId,
      @NotNull UUID producerEstablishmentId,
      @NotBlank @Size(max = 48) String provenanceCode,
      @NotBlank @Size(max = 48) String verificationStatus,
      @Size(max = 48) String semenType,
      UUID ownerId,
      Instant receivedAt,
      UUID sourceDocumentId) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
