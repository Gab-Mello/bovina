package com.bovina.parties.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.CounterpartyIdentifiers;
import com.bovina.parties.application.CounterpartyRegistration.OwnerScope;
import com.bovina.parties.domain.*;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/{product:clients|owners|suppliers|shipment-recipients}/{id}/identifiers")
public class CounterpartyIdentifierController {
  private final TenantAccess access;
  private final CounterpartyIdentifiers identifiers;

  public CounterpartyIdentifierController(
      TenantAccess access, CounterpartyIdentifiers identifiers) {
    this.access = access;
    this.identifiers = identifiers;
  }

  @PostMapping
  public ResponseEntity<CounterpartyIdentifier> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable String product,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "ANIMAL") OwnerScope scope,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody CounterpartyIdentifier body) {
    return ResponseEntity.status(201)
        .body(
            identifiers.register(
                context(jwt, organization, request), key, id, role(product, scope), body));
  }

  @GetMapping
  public PageResult<CounterpartyIdentifier> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable String product,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "ANIMAL") OwnerScope scope,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return identifiers.list(
        context(jwt, organization, request),
        id,
        role(product, scope),
        new SearchPage("", page, size));
  }

  private CounterpartyRole role(String product, OwnerScope scope) {
    return switch (product) {
      case "clients" -> CounterpartyRole.CLIENT;
      case "owners" ->
          scope == OwnerScope.ANIMAL
              ? CounterpartyRole.ANIMAL_OWNER
              : CounterpartyRole.MATERIAL_OWNER;
      case "suppliers" -> CounterpartyRole.SUPPLIER;
      case "shipment-recipients" -> CounterpartyRole.SHIPMENT_DESTINATION;
      default -> throw new IllegalArgumentException("Unsupported product route");
    };
  }

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
