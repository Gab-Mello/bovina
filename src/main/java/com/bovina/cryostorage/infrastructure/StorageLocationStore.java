package com.bovina.cryostorage.infrastructure;

import com.bovina.platform.application.ExecutionContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StorageLocationStore {
  private final JdbcTemplate jdbc;

  public StorageLocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ExecutionContext c, Location l, Instant now) {
    jdbc.update(
        "INSERT INTO storage_location(id,organization_id,establishment_id,operational_location_id,parent_id,type_code,code,name,status,created_at,created_by) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
        l.id(),
        c.tenantId(),
        l.establishmentId(),
        l.operationalLocationId(),
        l.parentId(),
        l.typeCode(),
        l.code(),
        l.name(),
        Timestamp.from(now),
        c.actorId());
  }

  public Location find(UUID tenant, UUID id) {
    var found =
        jdbc.query(
            "SELECT id,establishment_id,operational_location_id,parent_id,type_code,code,name,status,version FROM storage_location WHERE organization_id=? AND id=?",
            (rs, row) ->
                new Location(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getLong(9)),
            tenant,
            id);
    return found.isEmpty() ? null : found.getFirst();
  }

  public Location lock(UUID tenant, UUID id) {
    jdbc.query(
        "SELECT id FROM storage_location WHERE organization_id=? AND id=? FOR UPDATE",
        rs -> {},
        tenant,
        id);
    return find(tenant, id);
  }

  public boolean hasMaterialOrChildren(UUID tenant, UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM embryo_package WHERE organization_id=? AND current_location_id=?) OR EXISTS(SELECT 1 FROM storage_location WHERE organization_id=? AND parent_id=? AND status='ACTIVE')",
            Boolean.class,
            tenant,
            id,
            tenant,
            id));
  }

  public int deactivate(UUID tenant, UUID id, long expectedVersion) {
    return jdbc.update(
        "UPDATE storage_location SET status='INACTIVE',version=version+1 WHERE organization_id=? AND id=? AND status='ACTIVE' AND version=?",
        tenant,
        id,
        expectedVersion);
  }

  public List<Location> page(UUID tenant, int page, int size) {
    return jdbc.query(
        "SELECT id,establishment_id,operational_location_id,parent_id,type_code,code,name,status,version FROM storage_location WHERE organization_id=? ORDER BY code,id LIMIT ? OFFSET ?",
        (rs, row) ->
            new Location(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getLong(9)),
        tenant,
        size,
        page * size);
  }

  public record Location(
      UUID id,
      UUID establishmentId,
      UUID operationalLocationId,
      UUID parentId,
      String typeCode,
      String code,
      String name,
      String status,
      long version) {}
}
