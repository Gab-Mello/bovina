package com.bovina.distribution.api;

import com.bovina.distribution.application.Shipments;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {
  private final Shipments shipments;
  private final DistributionApiContext context;

  public ShipmentController(Shipments shipments, DistributionApiContext context) {
    this.shipments = shipments;
    this.context = context;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Shipments.View prepare(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Shipments.Preparation input) {
    return shipments.prepare(context.resolve(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public Shipments.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return shipments.get(context.resolve(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<Shipments.Summary> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return shipments.page(context.resolve(jwt, tenant, request), new SearchPage(q, page, size));
  }

  @PostMapping("/{id}:validate")
  public Shipments.Validation validate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody Validation input) {
    return shipments.validate(context.resolve(jwt, tenant, request), id, input.movementAt());
  }

  @PostMapping("/{id}:dispatch")
  public Shipments.View dispatch(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Shipments.Dispatch input) {
    return shipments.dispatch(context.resolve(jwt, tenant, request), key, id, input);
  }

  @PostMapping("/{id}:cancel")
  public Shipments.View cancel(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Shipments.Cancellation input) {
    return shipments.cancel(context.resolve(jwt, tenant, request), key, id, input);
  }

  @PostMapping("/{id}:receive")
  public Shipments.View receive(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Shipments.Receipt input) {
    return shipments.receive(context.resolve(jwt, tenant, request), key, id, input);
  }

  @PostMapping("/{id}:return")
  public Shipments.View returnPackage(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Shipments.Return input) {
    return shipments.returnPackage(context.resolve(jwt, tenant, request), key, id, input);
  }

  public record Validation(Instant movementAt) {}
}
