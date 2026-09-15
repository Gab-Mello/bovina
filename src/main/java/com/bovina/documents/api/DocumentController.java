package com.bovina.documents.api;

import com.bovina.documents.application.Documents;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
  private final Documents documents;
  private final TenantAccess access;

  public DocumentController(Documents documents, TenantAccess access) {
    this.documents = documents;
    this.access = access;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Documents.DocumentView register(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody Documents.Registration input) {
    return documents.register(context(jwt, tenant, request), key, input);
  }

  @PutMapping("/{id}/versions/{versionId}")
  @ResponseStatus(HttpStatus.CREATED)
  public Documents.VersionView upload(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @PathVariable UUID id,
      @PathVariable UUID versionId,
      @RequestParam long expectedVersion,
      @RequestHeader("X-File-Name") String fileName,
      @RequestHeader("Content-Type") String mimeType,
      @RequestHeader(value = "X-Source-Document-ID", required = false) UUID sourceDocumentId,
      @RequestBody byte[] bytes) {
    return documents.upload(
        context(jwt, tenant, request),
        key,
        id,
        new Documents.Upload(
            versionId, expectedVersion, fileName, mimeType, sourceDocumentId, bytes));
  }

  @GetMapping("/{id}")
  public Documents.DocumentView get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return documents.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<Documents.DocumentView> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return documents.page(context(jwt, tenant, request), new SearchPage(null, page, size));
  }

  @GetMapping("/{id}/versions")
  public PageResult<Documents.VersionView> versions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return documents.versions(context(jwt, tenant, request), id, new SearchPage(null, page, size));
  }

  @GetMapping("/{id}/versions/{versionId}/content")
  public ResponseEntity<byte[]> content(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @PathVariable UUID versionId) {
    var download = documents.download(context(jwt, tenant, request), id, versionId);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(download.version().originalFileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(download.bytes());
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }
}
