package com.bovina.cryostorage.api;

import com.bovina.cryostorage.application.EmbryoPackages;
import com.bovina.cryostorage.domain.EmbryoPackage;
import com.bovina.cryostorage.infrastructure.CryostorageFacts.PackageMember;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/embryo-packages")
public class EmbryoPackageController {
  private final CryostorageApiContext context;
  private final EmbryoPackages packages;

  public EmbryoPackageController(CryostorageApiContext context, EmbryoPackages packages) {
    this.context = context;
    this.packages = packages;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public EmbryoPackages.View create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody EmbryoPackage.Registration input) {
    return packages.create(context.resolve(jwt, tenant, request), key, input);
  }

  @PostMapping("/{id}/items:bulk")
  public EmbryoPackages.View add(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody EmbryoPackages.AddMembers input) {
    return packages.addMembers(context.resolve(jwt, tenant, request), key, id, input);
  }

  @PostMapping("/{id}:seal")
  public EmbryoPackages.View seal(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @RequestBody Version input) {
    return packages.seal(context.resolve(jwt, tenant, request), key, id, input.expectedVersion());
  }

  @GetMapping("/{id}")
  public EmbryoPackages.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return packages.get(context.resolve(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<EmbryoPackages.View> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return packages.page(context.resolve(jwt, tenant, request), new SearchPage(null, page, size));
  }

  @GetMapping("/{id}/items")
  public List<PackageMember> items(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return packages.members(context.resolve(jwt, tenant, request), id);
  }

  public record Version(long expectedVersion) {}
}
