package com.bovina.semen.infrastructure;

import com.bovina.platform.domain.DataProvenance;
import com.bovina.semen.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class SemenStore {
  private final JdbcTemplate jdbc;

  public SemenStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<ExternalEstablishmentReference> EXTERNAL =
      (rs, n) ->
          new ExternalEstablishmentReference(
              rs.getObject("id", UUID.class),
              rs.getObject("legal_party_id", UUID.class),
              rs.getString("name"),
              rs.getString("establishment_type"),
              rs.getString("registration_number"),
              rs.getString("registration_authority"),
              rs.getString("country"),
              rs.getString("verification_status"),
              rs.getObject("verification_document_id", UUID.class),
              rs.getString("status"),
              rs.getLong("version"));

  private static final RowMapper<SemenBatch> BATCH =
      (rs, n) ->
          new SemenBatch(
              rs.getObject("id", UUID.class),
              rs.getString("batch_code"),
              rs.getObject("sire_id", UUID.class),
              rs.getObject("producer_establishment_id", UUID.class),
              rs.getString("provenance_code"),
              rs.getString("verification_status"),
              rs.getString("semen_type"),
              rs.getObject("owner_id", UUID.class),
              instant(rs, "received_at"),
              rs.getString("status"),
              rs.getLong("version"),
              new DataProvenance(
                  DataProvenance.Origin.valueOf(rs.getString("origin_type")),
                  rs.getObject("source_document_id", UUID.class),
                  null,
                  null,
                  rs.getObject("recorded_by", UUID.class),
                  rs.getTimestamp("recorded_at").toInstant(),
                  null,
                  null,
                  null));

  public void insertExternal(
      UUID tenant, ExternalEstablishmentReference e, UUID actor, Instant now) {
    jdbc.update(
        """
        INSERT INTO external_establishment_reference(id,organization_id,legal_party_id,name,
          establishment_type,registration_number,registration_authority,country,verification_status,
          verification_document_id,status,version,recorded_by,recorded_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?)
        """,
        e.id(),
        tenant,
        e.legalPartyId(),
        e.name(),
        e.establishmentType(),
        e.registrationNumber(),
        e.registrationAuthority(),
        e.country(),
        e.verificationStatus(),
        e.verificationDocumentId(),
        e.status(),
        actor,
        Timestamp.from(now));
  }

  public Optional<ExternalEstablishmentReference> external(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM external_establishment_reference WHERE organization_id=? AND id=?",
            EXTERNAL,
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public List<ExternalEstablishmentReference> externalPage(UUID tenant, int size, int offset) {
    return jdbc.query(
        "SELECT * FROM external_establishment_reference WHERE organization_id=? ORDER BY lower(name),id LIMIT ? OFFSET ?",
        EXTERNAL,
        tenant,
        size,
        offset);
  }

  public void insertBatch(UUID tenant, SemenBatch b) {
    var p = b.provenance();
    jdbc.update(
        """
        INSERT INTO semen_batch(id,organization_id,batch_code,sire_id,producer_establishment_id,
          provenance_code,verification_status,semen_type,owner_id,received_at,status,version,
          origin_type,source_document_id,recorded_by,recorded_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)
        """,
        b.id(),
        tenant,
        b.batchCode(),
        b.sireId(),
        b.producerEstablishmentId(),
        b.provenanceCode(),
        b.verificationStatus(),
        b.semenType(),
        b.ownerId(),
        b.receivedAt() == null ? null : Timestamp.from(b.receivedAt()),
        b.status(),
        p.originType().name(),
        p.sourceDocumentId(),
        p.recordedByUserId(),
        Timestamp.from(p.recordedAt()));
  }

  public Optional<SemenBatch> batch(UUID tenant, UUID id) {
    return jdbc
        .query("SELECT * FROM semen_batch WHERE organization_id=? AND id=?", BATCH, tenant, id)
        .stream()
        .findFirst();
  }

  public List<SemenBatch> batchPage(UUID tenant, com.bovina.platform.application.SearchPage page) {
    return jdbc.query(
        "SELECT * FROM semen_batch WHERE organization_id=? AND batch_code ILIKE ? ORDER BY lower(batch_code),id LIMIT ? OFFSET ?",
        BATCH,
        tenant,
        page.pattern(),
        page.size(),
        page.offset());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
