package com.bovina.documents.application;

import com.bovina.audit.application.*;
import com.bovina.documents.domain.DocumentReference;
import com.bovina.documents.infrastructure.DocumentReferenceStore;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DocumentReferences {
  private final DocumentReferenceStore store;
  private final TenantAccess access;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public DocumentReferences(
      DocumentReferenceStore store,
      TenantAccess access,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.store = store;
    this.access = access;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public DocumentReference register(ExecutionContext context, UUID key, DocumentReference input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_DOCUMENT_REFERENCE_V1",
        input,
        DocumentReference.class,
        () -> {
          if (input.supersedesId() != null)
            requireReference(context.tenantId(), input.supersedesId());
          store.insert(context.tenantId(), input, context.actorId(), clock.instant());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  context,
                  clock.instant(),
                  "REGISTER",
                  "DOCUMENT_REFERENCE",
                  input.id(),
                  null,
                  null,
                  null,
                  "RECORDED"));
          return input;
        });
  }

  @Transactional(readOnly = true)
  public DocumentReference get(ExecutionContext context, UUID id) {
    access.require(context, "master-data:read");
    return find(context.tenantId(), id);
  }

  @Transactional(readOnly = true)
  public PageResult<DocumentReference> search(ExecutionContext context, SearchPage page) {
    access.require(context, "master-data:read");
    return new PageResult<>(store.search(context.tenantId(), page), page.page(), page.size());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireReference(UUID tenant, UUID id) {
    find(tenant, id);
  }

  private DocumentReference find(UUID tenant, UUID id) {
    return store
        .find(tenant, id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND,
                    "DOCUMENT_REFERENCE_NOT_FOUND",
                    "Document reference not found"));
  }
}
