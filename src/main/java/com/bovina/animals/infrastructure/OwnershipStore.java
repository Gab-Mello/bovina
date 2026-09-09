package com.bovina.animals.infrastructure;

import com.bovina.animals.domain.AnimalOwnershipAssignment;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class OwnershipStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<AnimalOwnershipAssignment> ROW =
      (rs, n) ->
          new AnimalOwnershipAssignment(
              rs.getObject("id", UUID.class),
              rs.getObject("animal_id", UUID.class),
              rs.getObject("owner_id", UUID.class),
              new EffectivePeriod(
                  rs.getObject("valid_from", LocalDate.class),
                  rs.getObject("valid_until", LocalDate.class)),
              rs.getObject("source_document_id", UUID.class),
              rs.getLong("version"));

  public OwnershipStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean overlaps(UUID tenant, UUID animal, UUID owner, EffectivePeriod period) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM animal_ownership_assignment WHERE organization_id=? AND animal_id=?
        AND owner_id=? AND valid_from<COALESCE(CAST(? AS date),'infinity'::date) AND (valid_until IS NULL OR valid_until>?))
        """,
            Boolean.class,
            tenant,
            animal,
            owner,
            period.until(),
            period.from()));
  }

  public void insert(UUID tenant, AnimalOwnershipAssignment a, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO animal_ownership_assignment(id,organization_id,animal_id,owner_id,valid_from,valid_until,source_document_id,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?)",
        a.id(),
        tenant,
        a.animalId(),
        a.ownerId(),
        a.period().from(),
        a.period().until(),
        a.sourceDocumentId(),
        actor,
        Timestamp.from(now));
  }

  public Optional<AnimalOwnershipAssignment> find(UUID tenant, UUID animal, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM animal_ownership_assignment WHERE organization_id=? AND animal_id=? AND id=?",
            ROW,
            tenant,
            animal,
            id)
        .stream()
        .findFirst();
  }

  public List<AnimalOwnershipAssignment> history(UUID tenant, UUID animal, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM animal_ownership_assignment WHERE organization_id=? AND animal_id=? ORDER BY valid_from,id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        animal,
        page.size(),
        page.offset());
  }

  public void end(UUID tenant, AnimalOwnershipAssignment a) {
    if (jdbc.update(
            "UPDATE animal_ownership_assignment SET valid_until=?,version=? WHERE organization_id=? AND id=? AND version=?",
            a.period().until(),
            a.version(),
            tenant,
            a.id(),
            a.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Ownership has changed");
  }
}
