package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.InventoryHolds;
import com.bovina.cryostorage.infrastructure.InventoryStore.Hold;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory-holds")
public class InventoryHoldController {
  private final CryostorageApiContext context;
  private final InventoryHolds holds;

  public InventoryHoldController(CryostorageApiContext context, InventoryHolds holds) {
    this.context = context;
    this.holds = holds;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InventoryHolds.View open(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Hold input) {
    return holds.open(context.resolve(jwt, tenant, request), key, input);
  }

  @PostMapping("/{id}:release")
  public InventoryHolds.View release(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Reason input) {
    return holds.release(context.resolve(jwt, tenant, request), key, id, input.reason());
  }

  @GetMapping("/{id}")
  public InventoryHolds.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return holds.get(context.resolve(jwt, tenant, request), id);
  }

  public record Reason(String reason) {}
}
