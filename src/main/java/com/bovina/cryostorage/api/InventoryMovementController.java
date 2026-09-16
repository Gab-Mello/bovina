package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.InventoryMovements;
import com.bovina.cryostorage.infrastructure.InventoryStore.MovementRow;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory-movements")
public class InventoryMovementController {
  private final CryostorageApiContext context;
  private final InventoryMovements movements;

  public InventoryMovementController(CryostorageApiContext context, InventoryMovements movements) {
    this.context = context;
    this.movements = movements;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InventoryMovements.Result record(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody InventoryMovements.Intent intent) {
    return movements.record(context.resolve(jwt, tenant, request), key, intent);
  }

  @PostMapping("/bulk")
  public InventoryMovements.Batch.Result bulk(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody InventoryMovements.Batch batch) {
    return movements.bulkRecord(context.resolve(jwt, tenant, request), key, batch);
  }

  @GetMapping("/packages/{packageId}")
  public PageResult<MovementRow> ledger(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID packageId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return movements.ledger(
        context.resolve(jwt, tenant, request), packageId, new SearchPage(null, page, size));
  }

  @GetMapping("/packages/{packageId}/projection-check")
  public ProjectionCheck projection(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID packageId) {
    return new ProjectionCheck(
        movements.projectionMatchesReplay(context.resolve(jwt, tenant, request), packageId));
  }

  public record ProjectionCheck(boolean matchesLedger) {}
}
