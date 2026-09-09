package com.bovina.animals.infrastructure;

import com.bovina.animals.domain.Breed;
import com.bovina.platform.application.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class BreedStore {
  private final JdbcTemplate jdbc;
  private static final RowMapper<Breed> ROW =
      (rs, n) ->
          new Breed(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              rs.getString("code"),
              rs.getString("status"),
              rs.getLong("version"));

  public BreedStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID tenant, Breed breed, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO breed(id,organization_id,name,code,species,status,recorded_by,recorded_at) VALUES (?,?,?,?,'BOVINE',?,?,?)",
        breed.id(),
        tenant,
        breed.name(),
        breed.code(),
        breed.status(),
        actor,
        Timestamp.from(now));
  }

  public Optional<Breed> find(UUID tenant, UUID id) {
    return jdbc
        .query("SELECT * FROM breed WHERE organization_id=? AND id=?", ROW, tenant, id)
        .stream()
        .findFirst();
  }

  public Optional<Breed> lock(UUID tenant, UUID id) {
    return jdbc
        .query("SELECT * FROM breed WHERE organization_id=? AND id=? FOR UPDATE", ROW, tenant, id)
        .stream()
        .findFirst();
  }

  public List<Breed> search(UUID tenant, SearchPage page) {
    return jdbc.query(
        "SELECT * FROM breed WHERE organization_id=? AND (name ILIKE ? OR code ILIKE ?) ORDER BY lower(name),id LIMIT ? OFFSET ?",
        ROW,
        tenant,
        page.pattern(),
        page.pattern(),
        page.size(),
        page.offset());
  }

  public void deactivate(UUID tenant, Breed breed) {
    if (jdbc.update(
            "UPDATE breed SET status=?,version=? WHERE organization_id=? AND id=? AND version=?",
            breed.status(),
            breed.version(),
            tenant,
            breed.id(),
            breed.version() - 1)
        != 1)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Breed has changed");
  }
}
