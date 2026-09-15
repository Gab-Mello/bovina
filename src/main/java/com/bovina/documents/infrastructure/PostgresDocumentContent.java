package com.bovina.documents.infrastructure;

import com.bovina.documents.application.DocumentContent;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresDocumentContent implements DocumentContent {
  private final JdbcTemplate jdbc;

  public PostgresDocumentContent(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void put(UUID organizationId, UUID versionId, byte[] content) {
    jdbc.update(
        "INSERT INTO document_blob(organization_id,version_id,content) VALUES (?,?,?)",
        organizationId,
        versionId,
        content);
  }

  @Override
  public byte[] get(UUID organizationId, UUID versionId) {
    return jdbc.queryForObject(
        "SELECT content FROM document_blob WHERE organization_id=? AND version_id=?",
        byte[].class,
        organizationId,
        versionId);
  }
}
