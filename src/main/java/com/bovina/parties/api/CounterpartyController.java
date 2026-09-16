package com.bovina.parties.api;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.*;
import com.bovina.parties.domain.*;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CounterpartyController {
  private final TenantAccess access;
  private final CounterpartyRegistration registrations;

  public CounterpartyController(TenantAccess access, CounterpartyRegistration registrations) {
    this.access = access;
    this.registrations = registrations;
  }

  @PostMapping("/owners")
  public ResponseEntity<CounterpartyView> registerOwner(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody RegistrationRequest body,
      @RequestParam CounterpartyRegistration.OwnerScope scope) {
    var result =
        registrations.registerOwner(
            context(jwt, organization, request),
            key,
            new RegisterCounterparty(
                body.id(),
                body.type(),
                body.displayName(),
                body.expectedVersion(),
                body.occurredAt()),
            scope);
    return ResponseEntity.created(URI.create("/api/v1/owners/" + result.id()))
        .eTag(Long.toString(result.version()))
        .body(result);
  }

  @GetMapping("/owners/{id}")
  public CounterpartyView getOwner(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam CounterpartyRegistration.OwnerScope scope) {
    return registrations.get(
        context(jwt, organization, request),
        id,
        scope == CounterpartyRegistration.OwnerScope.ANIMAL
            ? CounterpartyRole.ANIMAL_OWNER
            : CounterpartyRole.MATERIAL_OWNER);
  }

  @GetMapping("/owners")
  public PageResult<CounterpartyView> searchOwner(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam CounterpartyRegistration.OwnerScope scope) {
    return registrations.search(
        context(jwt, organization, request),
        scope == CounterpartyRegistration.OwnerScope.ANIMAL
            ? CounterpartyRole.ANIMAL_OWNER
            : CounterpartyRole.MATERIAL_OWNER,
        new SearchPage(q, page, size));
  }

  @PostMapping("/owners/{id}:archive")
  public CounterpartyView archiveOwner(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ArchiveRequest body,
      @RequestParam CounterpartyRegistration.OwnerScope scope) {
    return registrations.archive(
        context(jwt, organization, request),
        key,
        id,
        scope == CounterpartyRegistration.OwnerScope.ANIMAL
            ? CounterpartyRole.ANIMAL_OWNER
            : CounterpartyRole.MATERIAL_OWNER,
        body.expectedVersion());
  }

  @PostMapping("/suppliers")
  public ResponseEntity<CounterpartyView> registerSupplier(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody RegistrationRequest body) {
    var result =
        registrations.registerSupplier(
            context(jwt, organization, request),
            key,
            new RegisterCounterparty(
                body.id(),
                body.type(),
                body.displayName(),
                body.expectedVersion(),
                body.occurredAt()));
    return ResponseEntity.created(URI.create("/api/v1/suppliers/" + result.id()))
        .eTag(Long.toString(result.version()))
        .body(result);
  }

  @GetMapping("/suppliers/{id}")
  public CounterpartyView getSupplier(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return registrations.get(context(jwt, organization, request), id, CounterpartyRole.SUPPLIER);
  }

  @GetMapping("/suppliers")
  public PageResult<CounterpartyView> searchSupplier(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return registrations.search(
        context(jwt, organization, request),
        CounterpartyRole.SUPPLIER,
        new SearchPage(q, page, size));
  }

  @PostMapping("/suppliers/{id}:archive")
  public CounterpartyView archiveSupplier(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ArchiveRequest body) {
    return registrations.archive(
        context(jwt, organization, request),
        key,
        id,
        CounterpartyRole.SUPPLIER,
        body.expectedVersion());
  }

  @PostMapping("/shipment-recipients")
  public ResponseEntity<CounterpartyView> registerShipmentRecipient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody RegistrationRequest body) {
    var result =
        registrations.registerShipmentRecipient(
            context(jwt, organization, request),
            key,
            new RegisterCounterparty(
                body.id(),
                body.type(),
                body.displayName(),
                body.expectedVersion(),
                body.occurredAt()));
    return ResponseEntity.created(URI.create("/api/v1/shipment-recipients/" + result.id()))
        .eTag(Long.toString(result.version()))
        .body(result);
  }

  @GetMapping("/shipment-recipients/{id}")
  public CounterpartyView getShipmentRecipient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return registrations.get(
        context(jwt, organization, request), id, CounterpartyRole.SHIPMENT_DESTINATION);
  }

  @GetMapping("/shipment-recipients")
  public PageResult<CounterpartyView> searchShipmentRecipient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return registrations.search(
        context(jwt, organization, request),
        CounterpartyRole.SHIPMENT_DESTINATION,
        new SearchPage(q, page, size));
  }

  @PostMapping("/shipment-recipients/{id}:archive")
  public CounterpartyView archiveShipmentRecipient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ArchiveRequest body) {
    return registrations.archive(
        context(jwt, organization, request),
        key,
        id,
        CounterpartyRole.SHIPMENT_DESTINATION,
        body.expectedVersion());
  }

  @PostMapping("/clients:register")
  public CounterpartyView registerExistingClient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody RegistrationRequest body) {
    return registrations.registerClient(
        context(jwt, organization, request),
        key,
        new RegisterCounterparty(
            body.id(), body.type(), body.displayName(), body.expectedVersion(), body.occurredAt()));
  }

  @GetMapping("/clients")
  public PageResult<CounterpartyView> clients(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return registrations.search(
        context(jwt, organization, request),
        CounterpartyRole.CLIENT,
        new SearchPage(q, page, size));
  }

  @PostMapping("/clients/{id}:update")
  public CounterpartyView updateClient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody UpdateRequest body) {
    return registrations.updateClient(
        context(jwt, organization, request),
        key,
        id,
        new CounterpartyRegistration.UpdateClient(
            body.expectedVersion(), body.displayName(), body.legalName(), body.address()));
  }

  @PostMapping("/clients/{id}:archive")
  public CounterpartyView archiveClient(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ArchiveRequest body) {
    return registrations.archive(
        context(jwt, organization, request),
        key,
        id,
        CounterpartyRole.CLIENT,
        body.expectedVersion());
  }

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }

  public record RegistrationRequest(
      @NotNull UUID id,
      @NotNull ClientType type,
      @NotBlank @Size(max = 200) String displayName,
      @PositiveOrZero Long expectedVersion,
      @NotNull Instant occurredAt) {}

  public record ArchiveRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  public record UpdateRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max = 200) String displayName,
      @Size(max = 200) String legalName,
      com.bovina.platform.domain.Address address) {}
}
