package com.bovina.documents.infrastructure;

import com.bovina.documents.application.Documents.DocumentView;
import com.bovina.documents.application.Documents.VersionView;
import com.bovina.platform.application.SearchPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentStore {
  private final JdbcTemplate jdbc;

  public DocumentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID tenant, UUID id, String type, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO document(id,organization_id,type_code,status,created_at,created_by) VALUES (?,? ,?,'ACTIVE',?,?)",
        id,
        tenant,
        type,
        Timestamp.from(now),
        actor);
  }

  public DocumentView find(UUID tenant, UUID id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT id,type_code,status,current_version_id,version,created_at FROM document WHERE organization_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new DocumentView(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getObject(4, UUID.class),
                    rs.getLong(5),
                    rs.getTimestamp(6).toInstant()),
            tenant,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<DocumentView> page(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT id,type_code,status,current_version_id,version,created_at FROM document WHERE organization_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        (rs, n) ->
            new DocumentView(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, UUID.class),
                rs.getLong(5),
                rs.getTimestamp(6).toInstant()),
        tenant,
        page.size(),
        page.offset());
  }

  public void insertVersion(
      UUID tenant,
      UUID documentId,
      UUID versionId,
      int number,
      String name,
      String mime,
      long size,
      String checksum,
      UUID sourceDocumentId,
      UUID actor,
      Instant now) {
    jdbc.update(
        "INSERT INTO document_version(id,organization_id,document_id,version_number,original_file_name,mime_type,size_bytes,checksum,origin_type,source_document_id,uploaded_at,uploaded_by) VALUES (?,?,?,?,?,?,?,?,'MANUAL',?,?,?)",
        versionId,
        tenant,
        documentId,
        number,
        name,
        mime,
        size,
        checksum,
        sourceDocumentId,
        Timestamp.from(now),
        actor);
  }

  public void advance(UUID tenant, UUID documentId, UUID versionId, long expectedVersion) {
    if (jdbc.update(
            "UPDATE document SET current_version_id=?,version=version+1 WHERE organization_id=? AND id=? AND status='ACTIVE' AND version=?",
            versionId,
            tenant,
            documentId,
            expectedVersion)
        != 1) throw new IllegalStateException("Locked document version changed");
  }

  public VersionView version(UUID tenant, UUID documentId, UUID versionId) {
    var rows =
        jdbc.query(
            "SELECT id,document_id,version_number,original_file_name,mime_type,size_bytes,checksum,uploaded_at FROM document_version WHERE organization_id=? AND document_id=? AND id=?",
            (rs, n) ->
                new VersionView(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getLong(6),
                    rs.getString(7),
                    rs.getTimestamp(8).toInstant()),
            tenant,
            documentId,
            versionId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<VersionView> versions(UUID tenant, UUID documentId, SearchPage page) {
    return jdbc.query(
        "SELECT id,document_id,version_number,original_file_name,mime_type,size_bytes,checksum,uploaded_at FROM document_version WHERE organization_id=? AND document_id=? ORDER BY version_number DESC LIMIT ? OFFSET ?",
        (rs, n) ->
            new VersionView(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getInt(3),
                rs.getString(4),
                rs.getString(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getTimestamp(8).toInstant()),
        tenant,
        documentId,
        page.size(),
        page.offset());
  }
}
