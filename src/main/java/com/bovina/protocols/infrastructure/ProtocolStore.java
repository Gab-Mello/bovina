package com.bovina.protocols.infrastructure;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import com.bovina.protocols.application.ProtocolWithdrawal;
import com.bovina.protocols.domain.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class ProtocolStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<ProtocolDefinition> DEFINITION =
      (rs, n) ->
          new ProtocolDefinition(
              rs.getObject("id", UUID.class),
              rs.getString("purpose"),
              rs.getString("name"),
              rs.getString("reference"),
              rs.getString("status"),
              rs.getLong("version"));
  private static final RowMapper<ProtocolVersion> VERSION =
      (rs, n) ->
          new ProtocolVersion(
              rs.getObject("id", UUID.class),
              rs.getObject("definition_id", UUID.class),
              rs.getString("revision"),
              new EffectivePeriod(
                  rs.getObject("effective_from", LocalDate.class),
                  rs.getObject("effective_until", LocalDate.class)),
              rs.getString("content_reference"),
              rs.getString("checksum"),
              rs.getObject("document_id", UUID.class),
              rs.getObject("published_by", UUID.class),
              rs.getTimestamp("published_at").toInstant());

  public ProtocolStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void withdraw(UUID tenant, ProtocolWithdrawal withdrawal) {
    jdbc.update(
        "INSERT INTO protocol_version_withdrawal(organization_id,version_id,reason,withdrawn_by,withdrawn_at) VALUES (?,?,?,?,?)",
        tenant,
        withdrawal.versionId(),
        withdrawal.reason(),
        withdrawal.withdrawnBy(),
        Timestamp.from(withdrawal.withdrawnAt()));
  }

  public Optional<ProtocolWithdrawal> withdrawal(UUID tenant, UUID version) {
    return jdbc
        .query(
            "SELECT * FROM protocol_version_withdrawal WHERE organization_id=? AND version_id=?",
            (rs, n) ->
                new ProtocolWithdrawal(
                    rs.getObject("version_id", UUID.class),
                    rs.getString("reason"),
                    rs.getObject("withdrawn_by", UUID.class),
                    rs.getTimestamp("withdrawn_at").toInstant()),
            tenant,
            version)
        .stream()
        .findFirst();
  }

  public void insertDefinition(UUID tenant, ProtocolDefinition d, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO protocol_definition(id,organization_id,purpose,name,reference,status,version,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,0,?,?)",
        d.id(),
        tenant,
        d.purpose(),
        d.name(),
        d.reference(),
        d.status(),
        actor,
        Timestamp.from(now));
  }

  public Optional<ProtocolDefinition> find(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM protocol_definition WHERE organization_id=? AND id=?",
            DEFINITION,
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public Optional<ProtocolDefinition> lock(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM protocol_definition WHERE organization_id=? AND id=? FOR UPDATE",
            DEFINITION,
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public List<ProtocolDefinition> search(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM protocol_definition WHERE organization_id=? AND (name ILIKE ? OR purpose ILIKE ?) ORDER BY purpose,lower(name),id LIMIT ? OFFSET ?",
        DEFINITION,
        tenant,
        page.pattern(),
        page.pattern(),
        page.size(),
        page.offset());
  }

  public void deactivate(UUID tenant, ProtocolDefinition d) {
    if (jdbc.update(
            "UPDATE protocol_definition SET status=?,version=? WHERE organization_id=? AND id=? AND version=?",
            d.status(),
            d.version(),
            tenant,
            d.id(),
            d.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "Protocol definition has changed");
  }

  public void publish(UUID tenant, ProtocolVersion v) {
    jdbc.update(
        "INSERT INTO protocol_version(id,organization_id,definition_id,revision,effective_from,effective_until,content_reference,checksum,document_id,published_by,published_at,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED')",
        v.id(),
        tenant,
        v.definitionId(),
        v.revision(),
        v.effectivePeriod().from(),
        v.effectivePeriod().until(),
        v.contentReference(),
        v.checksum(),
        v.documentId(),
        v.publishedBy(),
        Timestamp.from(v.publishedAt()));
  }

  public Optional<ProtocolVersion> version(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM protocol_version WHERE organization_id=? AND id=?", VERSION, tenant, id)
        .stream()
        .findFirst();
  }

  public List<ProtocolVersion> versions(UUID tenant, UUID definition, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM protocol_version WHERE organization_id=? AND definition_id=? ORDER BY published_at,id LIMIT ? OFFSET ?",
        VERSION,
        tenant,
        definition,
        page.size(),
        page.offset());
  }
}
