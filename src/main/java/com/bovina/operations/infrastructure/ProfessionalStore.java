package com.bovina.operations.infrastructure;

import com.bovina.operations.domain.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class ProfessionalStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<Professional> PROFESSIONAL =
      (rs, n) ->
          new Professional(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              Professional.Type.valueOf(rs.getString("professional_type")),
              rs.getObject("linked_user_id", UUID.class),
              rs.getString("status"),
              rs.getLong("version"));
  private static final RowMapper<ProfessionalCredential> CREDENTIAL =
      (rs, n) ->
          new ProfessionalCredential(
              rs.getObject("id", UUID.class),
              rs.getObject("professional_id", UUID.class),
              rs.getString("issuer"),
              rs.getString("jurisdiction"),
              rs.getString("number"),
              new EffectivePeriod(
                  rs.getObject("valid_from", LocalDate.class),
                  rs.getObject("valid_until", LocalDate.class)),
              rs.getObject("document_id", UUID.class));

  public ProfessionalStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean hasMembership(UUID tenant, UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM organization_membership WHERE organization_id=? AND user_account_id=?)",
            Boolean.class,
            tenant,
            user));
  }

  public void insert(UUID tenant, Professional p, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO professional(id,organization_id,name,professional_type,linked_user_id,status,version,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,0,?,?)",
        p.id(),
        tenant,
        p.name(),
        p.professionalType().name(),
        p.linkedUserId(),
        p.status(),
        actor,
        Timestamp.from(now));
  }

  public Optional<Professional> find(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM professional WHERE organization_id=? AND id=?", PROFESSIONAL, tenant, id)
        .stream()
        .findFirst();
  }

  public Optional<Professional> lock(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM professional WHERE organization_id=? AND id=? FOR UPDATE",
            PROFESSIONAL,
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public List<Professional> search(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM professional WHERE organization_id=? AND name ILIKE ? ORDER BY lower(name),id LIMIT ? OFFSET ?",
        PROFESSIONAL,
        tenant,
        page.pattern(),
        page.size(),
        page.offset());
  }

  public void deactivate(UUID tenant, Professional p) {
    if (jdbc.update(
            "UPDATE professional SET status=?,version=? WHERE organization_id=? AND id=? AND version=?",
            p.status(),
            p.version(),
            tenant,
            p.id(),
            p.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "CONCURRENT_WRITE_CONFLICT",
          "Professional has changed");
  }

  public void addCredential(UUID tenant, ProfessionalCredential c, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO professional_credential(id,organization_id,professional_id,issuer,jurisdiction,number,valid_from,valid_until,document_id,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        c.id(),
        tenant,
        c.professionalId(),
        c.issuer(),
        c.jurisdiction(),
        c.number(),
        c.period().from(),
        c.period().until(),
        c.documentId(),
        actor,
        Timestamp.from(now));
  }

  public Optional<ProfessionalCredential> credential(UUID tenant, UUID professional, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM professional_credential WHERE organization_id=? AND professional_id=? AND id=?",
            CREDENTIAL,
            tenant,
            professional,
            id)
        .stream()
        .findFirst();
  }

  public List<ProfessionalCredential> credentials(UUID tenant, UUID professional, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM professional_credential WHERE organization_id=? AND professional_id=? ORDER BY valid_from,id LIMIT ? OFFSET ?",
        CREDENTIAL,
        tenant,
        professional,
        page.size(),
        page.offset());
  }
}
