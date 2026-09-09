package com.bovina.documents.infrastructure;

import com.bovina.documents.domain.DocumentReference;
import com.bovina.platform.application.SearchPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentReferenceStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<DocumentReference> ROW =
      (rs, n) ->
          new DocumentReference(
              rs.getObject("id", UUID.class),
              rs.getString("type"),
              rs.getString("reference"),
              rs.getString("revision"),
              rs.getString("checksum"),
              rs.getObject("supersedes_id", UUID.class));

  public DocumentReferenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID tenant, DocumentReference reference, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO document_reference(id,organization_id,type,reference,revision,checksum,supersedes_id,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?)",
        reference.id(),
        tenant,
        reference.type(),
        reference.reference(),
        reference.revision(),
        reference.checksum(),
        reference.supersedesId(),
        actor,
        Timestamp.from(now));
  }

  public Optional<DocumentReference> find(UUID tenant, UUID id) {
    return jdbc
        .query("SELECT * FROM document_reference WHERE organization_id=? AND id=?", ROW, tenant, id)
        .stream()
        .findFirst();
  }

  public List<DocumentReference> search(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM document_reference WHERE organization_id=? AND reference ILIKE ? ORDER BY id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        page.pattern(),
        page.size(),
        page.offset());
  }
}
