package com.bovina.opu.api;

import com.bovina.identity.application.*;
import com.bovina.opu.application.OocyteTransports;
import com.bovina.opu.application.TransportView;
import com.bovina.opu.domain.TransportReceipt;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/oocyte-transports")
@ConditionalOnProperty(
    prefix = "bovina.opu.intake",
    name = "transport-enabled",
    havingValue = "true")
public class TransportReceiptController {
  private final TenantAccess access;
  private final OocyteTransports intake;

  public TransportReceiptController(TenantAccess access, OocyteTransports intake) {
    this.access = access;
    this.intake = intake;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TransportView record(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody TransportReceipt input) {
    return intake.recordTransport(context(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public TransportView get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return intake.transport(context(jwt, tenant, request), id);
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
