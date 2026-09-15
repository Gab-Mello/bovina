package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.ThawEvents;
import com.bovina.cryostorage.infrastructure.InventoryStore.Thaw;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/thaw-events")
public class ThawEventController {
  private final CryostorageApiContext context;
  private final ThawEvents thaw;

  public ThawEventController(CryostorageApiContext context, ThawEvents thaw) {
    this.context = context;
    this.thaw = thaw;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ThawEvents.Result thaw(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Thaw input) {
    return thaw.thaw(context.resolve(jwt, tenant, request), key, input);
  }
}
