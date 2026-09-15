package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.Cryopreservations;
import com.bovina.cryostorage.domain.CryopreservationBatch;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cryopreservation-events")
public class CryopreservationController {
  private final CryostorageApiContext context;
  private final Cryopreservations cryopreservations;

  public CryopreservationController(
      CryostorageApiContext context, Cryopreservations cryopreservations) {
    this.context = context;
    this.cryopreservations = cryopreservations;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Cryopreservations.Result cryopreserve(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody CryopreservationBatch batch) {
    return cryopreservations.cryopreserve(context.resolve(jwt, tenant, request), key, batch);
  }
}
