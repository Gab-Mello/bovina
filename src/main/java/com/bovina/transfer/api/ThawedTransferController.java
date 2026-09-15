package com.bovina.transfer.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.transfer.application.ThawedTransfers;
import com.bovina.transfer.domain.EmbryoTransfer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/thawed-transfers:perform")
public class ThawedTransferController {
  private final TenantAccess access;
  private final ThawedTransfers transfers;

  public ThawedTransferController(TenantAccess access, ThawedTransfers transfers) {
    this.access = access;
    this.transfers = transfers;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public EmbryoTransfer perform(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody ThawedTransfers.Intent intent) {
    var context =
        access.resolve(
            new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
            tenant,
            UUID.fromString((String) request.getAttribute("traceId")));
    return transfers.perform(context, key, intent);
  }
}
