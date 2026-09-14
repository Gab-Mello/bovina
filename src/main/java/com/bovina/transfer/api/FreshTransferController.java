package com.bovina.transfer.api;

import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.transfer.application.FreshTransfers;
import com.bovina.transfer.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class FreshTransferController {
  private final TenantAccess access;
  private final FreshTransfers transfers;

  public FreshTransferController(TenantAccess access, FreshTransfers transfers) {
    this.access = access;
    this.transfers = transfers;
  }

  @PostMapping("/transfers:bulk-reserve")
  public TransferBatches.Result reserve(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ReservationRequest input) {
    return transfers.reserve(context(jwt, tenant, request), key, input.value());
  }

  @PostMapping("/transfers:bulk-perform")
  public TransferBatches.Result perform(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody PerformanceRequest input) {
    return transfers.perform(context(jwt, tenant, request), key, input.value());
  }

  @PostMapping("/transfer-reservations/{id}:cancel")
  public FreshTransfers.ReservationView cancel(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @Valid @RequestBody CancelRequest input) {
    return transfers.cancel(
        context(jwt, tenant, request),
        key,
        id,
        new FreshTransfers.Cancel(input.expectedEmbryoVersion(), input.reason()));
  }

  @GetMapping("/transfers/{id}")
  public FreshTransfers.Details get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return transfers.get(context(jwt, tenant, request), id);
  }

  @GetMapping("/transfers")
  public PageResult<EmbryoTransfer> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(required = false) UUID recipientCycleId,
      @RequestParam(required = false) UUID embryoId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return transfers.search(
        context(jwt, tenant, request), recipientCycleId, embryoId, new SearchPage("", page, size));
  }

  public record ReservationRequest(
      @NotNull UUID batchId, @NotEmpty @Size(max = 100) List<@Valid ReservationItemRequest> items) {
    TransferBatches.Reservation value() {
      return new TransferBatches.Reservation(
          batchId, items.stream().map(ReservationItemRequest::value).toList());
    }
  }

  public record ReservationItemRequest(
      @NotNull UUID itemId,
      @NotNull UUID reservationId,
      @NotNull UUID embryoId,
      @NotNull UUID recipientCycleId,
      @PositiveOrZero long expectedEmbryoVersion) {
    TransferBatches.ReservationItem value() {
      return new TransferBatches.ReservationItem(
          itemId, reservationId, embryoId, recipientCycleId, expectedEmbryoVersion);
    }
  }

  public record PerformanceRequest(
      @NotNull UUID batchId, @NotEmpty @Size(max = 100) List<@Valid PerformanceItemRequest> items) {
    TransferBatches.Performance value() {
      return new TransferBatches.Performance(
          batchId, items.stream().map(PerformanceItemRequest::value).toList());
    }
  }

  public record PerformanceItemRequest(
      @NotNull UUID itemId,
      @NotNull UUID transferId,
      @NotNull UUID reservationId,
      @PositiveOrZero long expectedEmbryoVersion,
      @NotNull Instant performedAt,
      @NotBlank @Size(max = 64) String timezone,
      @NotNull UUID operatorProfessionalId,
      @Size(max = 1000) String notes) {
    TransferBatches.PerformanceItem value() {
      return new TransferBatches.PerformanceItem(
          itemId,
          transferId,
          reservationId,
          expectedEmbryoVersion,
          performedAt,
          timezone,
          operatorProfessionalId,
          notes);
    }
  }

  public record CancelRequest(
      @PositiveOrZero long expectedEmbryoVersion, @NotBlank @Size(max = 500) String reason) {}

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
