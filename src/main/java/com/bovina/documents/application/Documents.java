package com.bovina.documents.application;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.documents.infrastructure.DocumentStore;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.CommandReceipts;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import com.bovina.platform.application.StableIds;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Documents {
  public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

  private final DocumentStore store;
  private final DocumentContent content;
  private final DocumentReferences references;
  private final TenantAccess access;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public Documents(
      DocumentStore store,
      DocumentContent content,
      DocumentReferences references,
      TenantAccess access,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.store = store;
    this.content = content;
    this.references = references;
    this.access = access;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public DocumentView register(ExecutionContext c, UUID key, Registration input) {
    access.require(c, "documents:write");
    if (!key.equals(input.id())) throw rejected("DOCUMENT_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_DOCUMENT_V1",
        input,
        DocumentView.class,
        () -> {
          var now = now();
          store.insert(c.tenantId(), input.id(), input.typeCode(), c.actorId(), now);
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "REGISTER",
                  "DOCUMENT",
                  input.id(),
                  0L,
                  null,
                  null,
                  "ACTIVE"));
          return store.find(c.tenantId(), input.id(), false);
        });
  }

  @Transactional
  public VersionView upload(ExecutionContext c, UUID key, UUID documentId, Upload input) {
    access.require(c, "documents:write");
    if (!key.equals(input.versionId())) throw rejected("DOCUMENT_VERSION_KEY_MISMATCH");
    var checksum = checksum(input.bytes());
    var intent =
        new UploadIntent(
            documentId,
            input.versionId(),
            input.expectedVersion(),
            input.originalFileName(),
            input.mimeType(),
            input.sourceDocumentId(),
            input.bytes().length,
            checksum);
    return receipts.replayOrExecute(
        c,
        key,
        "UPLOAD_DOCUMENT_VERSION_V1",
        intent,
        VersionView.class,
        () -> {
          var document = require(c.tenantId(), documentId, true);
          if (!document.status().equals("ACTIVE") || document.version() != input.expectedVersion())
            throw conflict("DOCUMENT_VERSION_CHANGED");
          if (input.sourceDocumentId() != null)
            references.requireReference(c.tenantId(), input.sourceDocumentId());
          var now = now();
          store.insertVersion(
              c.tenantId(),
              documentId,
              input.versionId(),
              Math.toIntExact(document.version() + 1),
              input.originalFileName(),
              input.mimeType(),
              input.bytes().length,
              checksum,
              input.sourceDocumentId(),
              c.actorId(),
              now);
          content.put(c.tenantId(), input.versionId(), input.bytes());
          store.advance(c.tenantId(), documentId, input.versionId(), document.version());
          audit.record(
              new AuditEvent(
                  ids.next(),
                  c,
                  now,
                  "UPLOAD",
                  "DOCUMENT_VERSION",
                  input.versionId(),
                  document.version() + 1,
                  null,
                  null,
                  "RECORDED"));
          return store.version(c.tenantId(), documentId, input.versionId());
        });
  }

  @Transactional(readOnly = true)
  public DocumentView get(ExecutionContext c, UUID id) {
    access.require(c, "documents:read");
    return require(c.tenantId(), id, false);
  }

  @Transactional(readOnly = true)
  public VersionView version(ExecutionContext c, UUID documentId, UUID versionId) {
    access.require(c, "documents:read");
    var version = store.version(c.tenantId(), documentId, versionId);
    if (version == null) throw missing();
    return version;
  }

  @Transactional(readOnly = true)
  public PageResult<DocumentView> page(ExecutionContext c, SearchPage page) {
    access.require(c, "documents:read");
    return new PageResult<>(store.page(c.tenantId(), page), page.page(), page.size());
  }

  @Transactional(readOnly = true)
  public PageResult<VersionView> versions(ExecutionContext c, UUID id, SearchPage page) {
    access.require(c, "documents:read");
    require(c.tenantId(), id, false);
    return new PageResult<>(store.versions(c.tenantId(), id, page), page.page(), page.size());
  }

  @Transactional
  public Download download(ExecutionContext c, UUID documentId, UUID versionId) {
    access.require(c, "documents:read");
    require(c.tenantId(), documentId, false);
    var version = store.version(c.tenantId(), documentId, versionId);
    if (version == null) throw missing();
    var bytes = content.get(c.tenantId(), versionId);
    if (bytes.length != version.sizeBytes() || !checksum(bytes).equals(version.checksum()))
      throw new IllegalStateException(
          "Stored document content does not match its immutable checksum");
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            now(),
            "DOWNLOAD",
            "DOCUMENT_VERSION",
            versionId,
            (long) version.versionNumber(),
            null,
            null,
            "AUTHORIZED"));
    return new Download(version, bytes);
  }

  private DocumentView require(UUID tenant, UUID id, boolean lock) {
    var document = store.find(tenant, id, lock);
    if (document == null) throw missing();
    return document;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static String checksum(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM SHA-256 provider is unavailable", impossible);
    }
  }

  public record Registration(UUID id, String typeCode) {
    public Registration {
      StableIds.requireVersion7(id);
      if (typeCode == null || !typeCode.matches("[A-Z][A-Z0-9_]{0,63}"))
        throw rejected("INVALID_DOCUMENT_TYPE");
    }
  }

  public record Upload(
      UUID versionId,
      long expectedVersion,
      String originalFileName,
      String mimeType,
      UUID sourceDocumentId,
      byte[] bytes) {
    public Upload {
      StableIds.requireVersion7(versionId);
      if (expectedVersion < 0
          || originalFileName == null
          || originalFileName.isBlank()
          || originalFileName.length() > 255
          || originalFileName
              .chars()
              .anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c))
          || mimeType == null
          || mimeType.length() > 128
          || !mimeType.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")
          || bytes == null
          || bytes.length == 0
          || bytes.length > MAX_UPLOAD_BYTES) throw rejected("INVALID_DOCUMENT_UPLOAD");
      originalFileName = originalFileName.strip();
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record DocumentView(
      UUID id,
      String typeCode,
      String status,
      UUID currentVersionId,
      long version,
      Instant createdAt) {}

  public record VersionView(
      UUID id,
      UUID documentId,
      int versionNumber,
      String originalFileName,
      String mimeType,
      long sizeBytes,
      String checksum,
      Instant uploadedAt) {}

  public record Download(VersionView version, byte[] bytes) {
    public Download {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record UploadIntent(
      UUID documentId,
      UUID versionId,
      long expectedVersion,
      String originalFileName,
      String mimeType,
      UUID sourceDocumentId,
      int sizeBytes,
      String checksum) {}

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document not found");
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Document request is invalid");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Document version conflicts with current state");
  }
}
