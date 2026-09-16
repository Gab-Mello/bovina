package com.bovina.opu.api;

import com.bovina.identity.application.*;
import com.bovina.opu.application.*;
import com.bovina.opu.domain.OpuSession;
import com.bovina.platform.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/opu-sessions")
public class OpuController {
  private final TenantAccess access;
  private final OpuSessions sessions;
  private final RecordCollections collections;
  private final CompleteOpuSession completion;
  private final OocyteCollections queries;

  public OpuController(
      TenantAccess access,
      OpuSessions sessions,
      RecordCollections collections,
      CompleteOpuSession completion,
      OocyteCollections queries) {
    this.access = access;
    this.sessions = sessions;
    this.collections = collections;
    this.completion = completion;
    this.queries = queries;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OpuSessions.View open(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody OpuSession.Registration input) {
    return sessions.open(context(jwt, tenant, request), key, input);
  }

  @GetMapping("/{id}")
  public OpuSessions.View get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return sessions.get(context(jwt, tenant, request), id);
  }

  @GetMapping
  public PageResult<OpuSessions.View> page(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return sessions.page(context(jwt, tenant, request), new SearchPage(null, page, size));
  }

  @PostMapping("/{id}:start")
  public OpuSessions.View start(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Version input) {
    return sessions.transition(
        context(jwt, tenant, request), key, id, input.expectedVersion(), OpuSessions.Action.START);
  }

  @PostMapping("/{id}:cancel")
  public OpuSessions.View cancel(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Version input) {
    return sessions.transition(
        context(jwt, tenant, request), key, id, input.expectedVersion(), OpuSessions.Action.CANCEL);
  }

  @PostMapping("/{id}:complete")
  public OpuSessions.View complete(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Version input) {
    return completion.complete(context(jwt, tenant, request), key, id, input.expectedVersion());
  }

  @PostMapping("/{id}/collections:dry-run")
  public CollectionBatch.Result preview(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody CollectionBatch batch) {
    return collections.preview(context(jwt, tenant, request), id, batch);
  }

  @PostMapping("/{id}/collections:bulk")
  public CollectionBatch.Result record(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody CollectionBatch batch) {
    return collections.record(context(jwt, tenant, request), key, id, batch);
  }

  @GetMapping("/{id}/collections")
  public PageResult<OocyteCollections.View> collections(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queries.page(context(jwt, tenant, request), id, new SearchPage(null, page, size));
  }

  @GetMapping("/{id}/summary")
  public OpuSessions.Summary summary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Organization-ID", required = false) UUID tenant,
      HttpServletRequest request,
      @PathVariable UUID id) {
    return sessions.summary(context(jwt, tenant, request), id);
  }

  private ExecutionContext context(Jwt jwt, UUID tenant, HttpServletRequest request) {
    return access.resolve(
        new AuthenticatedIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
        tenant,
        UUID.fromString((String) request.getAttribute("traceId")));
  }

  public record Version(
      @jakarta.validation.constraints.NotNull @PositiveOrZero Long expectedVersion) {}
}
