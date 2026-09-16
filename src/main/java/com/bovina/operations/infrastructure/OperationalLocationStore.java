package com.bovina.operations.infrastructure;

import com.bovina.operations.domain.OperationalLocation;
import com.bovina.platform.application.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class OperationalLocationStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<OperationalLocation> ROW =
      (rs, n) ->
          new OperationalLocation(
              rs.getObject("id", UUID.class),
              rs.getObject("establishment_id", UUID.class),
              rs.getString("name"),
              OperationalLocation.Type.valueOf(rs.getString("type")),
              rs.getString("timezone"),
              rs.getString("status"),
              rs.getLong("version"));

  public OperationalLocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID tenant, OperationalLocation l, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO operational_location(id,organization_id,establishment_id,name,type,timezone,status,version,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,0,?,?)",
        l.id(),
        tenant,
        l.establishmentId(),
        l.name(),
        l.type().name(),
        l.timezone(),
        l.status(),
        actor,
        Timestamp.from(now));
  }

  public Optional<OperationalLocation> find(UUID tenant, UUID establishment, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM operational_location WHERE organization_id=? AND establishment_id=? AND id=?",
            ROW,
            tenant,
            establishment,
            id)
        .stream()
        .findFirst();
  }

  public List<OperationalLocation> search(UUID tenant, UUID establishment, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM operational_location WHERE organization_id=? AND establishment_id=? AND name ILIKE ? ORDER BY lower(name),id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        establishment,
        page.pattern(),
        page.size(),
        page.offset());
  }

  public void deactivate(UUID tenant, OperationalLocation l) {
    if (jdbc.update(
            "UPDATE operational_location SET status=?,version=? WHERE organization_id=? AND id=? AND version=?",
            l.status(),
            l.version(),
            tenant,
            l.id(),
            l.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Location has changed");
  }
}
