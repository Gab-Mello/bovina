package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.StorageLocations;
import com.bovina.cryostorage.infrastructure.StorageLocationStore.Location;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/storage-locations")
public class StorageLocationController {
  private final CryostorageApiContext context;
  private final StorageLocations locations;

  public StorageLocationController(CryostorageApiContext context, StorageLocations locations) {
    this.context = context;
    this.locations = locations;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Location create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody StorageLocations.Registration input) {
    return locations.register(context.resolve(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public Location get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return locations.get(context.resolve(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<Location> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return locations.page(context.resolve(jwt, tenant, request), new SearchPage(null, page, size));
  }

  @PostMapping("/{id}:deactivate")
  public Location deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Version input) {
    return locations.deactivate(
        context.resolve(jwt, tenant, request), key, id, input.expectedVersion());
  }

  public record Version(long expectedVersion) {}
}
