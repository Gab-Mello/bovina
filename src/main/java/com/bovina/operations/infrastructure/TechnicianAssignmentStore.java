package com.bovina.operations.infrastructure;

import com.bovina.operations.domain.ResponsibleTechnicianAssignment;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class TechnicianAssignmentStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<ResponsibleTechnicianAssignment> ROW =
      (rs, n) ->
          new ResponsibleTechnicianAssignment(
              rs.getObject("id", UUID.class),
              rs.getObject("establishment_id", UUID.class),
              rs.getObject("professional_id", UUID.class),
              rs.getObject("credential_id", UUID.class),
              rs.getString("credential_issuer"),
              rs.getString("credential_jurisdiction"),
              rs.getString("credential_number"),
              rs.getObject("document_id", UUID.class),
              new EffectivePeriod(
                  rs.getObject("valid_from", LocalDate.class),
                  rs.getObject("valid_until", LocalDate.class)),
              rs.getLong("version"));

  public TechnicianAssignmentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean overlaps(
      UUID tenant, UUID establishment, UUID professional, EffectivePeriod period) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM responsible_technician_assignment WHERE organization_id=?
        AND establishment_id=? AND professional_id=? AND valid_from < COALESCE(CAST(? AS date),'infinity'::date)
        AND (valid_until IS NULL OR valid_until > ?))
        """,
            Boolean.class,
            tenant,
            establishment,
            professional,
            period.until(),
            period.from()));
  }

  public void insert(UUID tenant, ResponsibleTechnicianAssignment a, UUID actor, Instant now) {
    jdbc.update(
        """
        INSERT INTO responsible_technician_assignment(id,organization_id,establishment_id,professional_id,
        credential_id,credential_issuer,credential_jurisdiction,credential_number,document_id,
        valid_from,valid_until,version,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?)
        """,
        a.id(),
        tenant,
        a.establishmentId(),
        a.professionalId(),
        a.credentialId(),
        a.credentialIssuer(),
        a.credentialJurisdiction(),
        a.credentialNumber(),
        a.documentId(),
        a.period().from(),
        a.period().until(),
        actor,
        Timestamp.from(now));
  }

  public Optional<ResponsibleTechnicianAssignment> find(UUID tenant, UUID establishment, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM responsible_technician_assignment WHERE organization_id=? AND establishment_id=? AND id=?",
            ROW,
            tenant,
            establishment,
            id)
        .stream()
        .findFirst();
  }

  public List<ResponsibleTechnicianAssignment> history(
      UUID tenant, UUID establishment, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM responsible_technician_assignment WHERE organization_id=? AND establishment_id=? ORDER BY valid_from,id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        establishment,
        page.size(),
        page.offset());
  }

  public void end(UUID tenant, ResponsibleTechnicianAssignment a) {
    if (jdbc.update(
            "UPDATE responsible_technician_assignment SET valid_until=?,version=? WHERE organization_id=? AND id=? AND version=?",
            a.period().until(),
            a.version(),
            tenant,
            a.id(),
            a.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Assignment has changed");
  }
}
