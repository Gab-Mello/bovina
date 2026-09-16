package com.bovina.animals.infrastructure;

import com.bovina.animals.domain.*;
import com.bovina.platform.application.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class AnimalIdentifierStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<AnimalIdentifier> ROW =
      (rs, n) ->
          new AnimalIdentifier(
              rs.getObject("id", UUID.class),
              rs.getObject("animal_id", UUID.class),
              AnimalIdentifier.Type.valueOf(rs.getString("type")),
              new IdentifierValue(rs.getString("value"), rs.getString("issuer")),
              rs.getObject("valid_from", LocalDate.class),
              rs.getObject("valid_until", LocalDate.class),
              AnimalIdentifier.Status.valueOf(rs.getString("status")),
              rs.getLong("version"));

  public AnimalIdentifierStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID tenant, AnimalIdentifier i, UUID actor, Instant now) {
    jdbc.update(
        """
      INSERT INTO animal_identifier(id,organization_id,animal_id,type,value,normalized_value,issuer,status,
          valid_from,valid_until,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?,?)
      """,
        i.id(),
        tenant,
        i.animalId(),
        i.type().name(),
        i.identifier().value(),
        i.identifier().normalized(),
        i.identifier().issuer(),
        i.validFrom(),
        i.validUntil(),
        actor,
        Timestamp.from(now));
  }

  public Optional<AnimalIdentifier> find(UUID tenant, UUID animal, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM animal_identifier WHERE organization_id=? AND animal_id=? AND id=?",
            ROW,
            tenant,
            animal,
            id)
        .stream()
        .findFirst();
  }

  public List<AnimalIdentifier> history(UUID tenant, UUID animal, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM animal_identifier WHERE organization_id=? AND animal_id=? ORDER BY recorded_at,id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        animal,
        page.size(),
        page.offset());
  }

  public void retire(UUID tenant, AnimalIdentifier i, UUID actor, Instant now, String reason) {
    if (jdbc.update(
            "UPDATE animal_identifier SET status=?,version=?,retired_by=?,retired_at=?,retirement_reason=? WHERE organization_id=? AND id=? AND version=?",
            i.status().name(),
            i.version(),
            actor,
            Timestamp.from(now),
            reason,
            tenant,
            i.id(),
            i.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Identifier has changed");
  }
}
