package com.bovina.analytics.api;

import com.bovina.analytics.application.DataQuality;
import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/data-quality")
public class DataQualityController {
  private final TenantAccess access;
  private final DataQuality quality;

  public DataQualityController(TenantAccess access, DataQuality quality) {
    this.access = access;
    this.quality = quality;
  }

  @GetMapping
  public PageResult<DataQuality.Issue> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var context =
        access.resolve(
            new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
            tenant,
            UUID.fromString((String) request.getAttribute("traceId")));
    return quality.search(context, new SearchPage(q, page, size));
  }
}
