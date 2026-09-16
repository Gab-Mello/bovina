package com.bovina.documents.api;

import com.bovina.documents.application.DocumentReferences;
import com.bovina.documents.domain.DocumentReference;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-references")
public class DocumentReferenceController {
  private final TenantAccess access;
  private final DocumentReferences documents;

  public DocumentReferenceController(TenantAccess access, DocumentReferences documents) {
    this.access = access;
    this.documents = documents;
  }

  @PostMapping
  public ResponseEntity<DocumentReference> register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody DocumentReference body) {
    var result = documents.register(context(jwt, organization, request), key, body);
    return ResponseEntity.created(URI.create("/api/v1/document-references/" + result.id()))
        .body(result);
  }

  @GetMapping("/{id}")
  public DocumentReference get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return documents.get(context(jwt, organization, request), id);
  }

  @GetMapping
  public PageResult<DocumentReference> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID organization,
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return documents.search(context(jwt, organization, request), new SearchPage(q, page, size));
  }

  private ExecutionContext context(Jwt jwt, UUID organization, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        organization,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
