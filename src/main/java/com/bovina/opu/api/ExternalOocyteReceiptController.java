package com.bovina.opu.api;

import com.bovina.identity.application.*;
import com.bovina.opu.application.ExternalReceiptView;
import com.bovina.opu.application.ReceiveExternalOocytes;
import com.bovina.opu.domain.ExternalOocyteReceipt;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/external-oocyte-receipts")
@ConditionalOnProperty(
    prefix = "bovina.opu.intake",
    name = "external-receipt-enabled",
    havingValue = "true")
public class ExternalOocyteReceiptController {
  private final TenantAccess access;
  private final ReceiveExternalOocytes intake;

  public ExternalOocyteReceiptController(TenantAccess access, ReceiveExternalOocytes intake) {
    this.access = access;
    this.intake = intake;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ExternalReceiptView record(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody ExternalOocyteReceipt input) {
    return intake.recordExternal(context(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public ExternalReceiptView get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return intake.external(context(jwt, tenant, request), id);
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
