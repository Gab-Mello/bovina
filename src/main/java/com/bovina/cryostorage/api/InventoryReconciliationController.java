package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.InventoryMovements;
import com.bovina.cryostorage.application.InventoryReconciliations;
import com.bovina.cryostorage.infrastructure.ReconciliationStore.Discrepancy;
import com.bovina.cryostorage.infrastructure.ReconciliationStore.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory-reconciliations")
public class InventoryReconciliationController {
  private final CryostorageApiContext context;
  private final InventoryReconciliations reconciliations;

  public InventoryReconciliationController(
      CryostorageApiContext context, InventoryReconciliations reconciliations) {
    this.context = context;
    this.reconciliations = reconciliations;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Session open(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody InventoryReconciliations.Open input) {
    return reconciliations.open(context.resolve(jwt, tenant, request), key, input);
  }

  @PostMapping("/{id}/observations:bulk")
  public InventoryReconciliations.ObservationBatch.Result observe(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody InventoryReconciliations.ObservationBatch input) {
    return reconciliations.observe(context.resolve(jwt, tenant, request), key, id, input);
  }

  @PostMapping("/{id}:close")
  public Session close(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Version input) {
    return reconciliations.close(
        context.resolve(jwt, tenant, request), key, id, input.expectedVersion());
  }

  @GetMapping("/{id}/discrepancies")
  public List<Discrepancy> differences(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return reconciliations.differences(context.resolve(jwt, tenant, request), id);
  }

  @PostMapping("/{id}:resolve")
  public InventoryMovements.Result resolve(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody InventoryMovements.Intent movement) {
    return reconciliations.resolve(context.resolve(jwt, tenant, request), key, id, movement);
  }

  public record Version(long expectedVersion) {}
}
