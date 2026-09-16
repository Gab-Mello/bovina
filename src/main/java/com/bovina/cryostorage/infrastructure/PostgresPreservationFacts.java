package com.bovina.cryostorage.infrastructure;

import com.bovina.embryology.application.PreservationFacts;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresPreservationFacts implements PreservationFacts {
  private final NamedParameterJdbcTemplate jdbc;
  private final InventoryStore inventory;

  public PostgresPreservationFacts(NamedParameterJdbcTemplate jdbc, InventoryStore inventory) {
    this.jdbc = jdbc;
    this.inventory = inventory;
  }

  @Override
  public Set<UUID> cryopreservedEmbryos(UUID tenant, Collection<UUID> embryoIds) {
    if (embryoIds.isEmpty()) return Set.of();
    return Set.copyOf(
        new HashSet<>(
            jdbc.query(
                "SELECT DISTINCT embryo_id FROM cryopreservation_item WHERE organization_id=:tenant AND embryo_id IN (:ids)",
                java.util.Map.of("tenant", tenant, "ids", embryoIds),
                (rs, row) -> rs.getObject(1, UUID.class))));
  }

  @Override
  public ThawEvidence thawEvidence(UUID tenant, UUID thawEventId, UUID embryoId) {
    var at = inventory.thawEvidence(tenant, thawEventId, embryoId);
    return at == null ? null : new ThawEvidence(at);
  }

  @Override
  public State state(UUID tenant, UUID embryoId) {
    var rows =
        jdbc.getJdbcTemplate()
            .query(
                "SELECT (SELECT EXISTS(SELECT 1 FROM cryopreservation_item WHERE organization_id=? AND embryo_id=?)), (SELECT EXISTS(SELECT 1 FROM thaw_event_item WHERE organization_id=? AND embryo_id=?)), (SELECT p.current_location_id FROM package_item i JOIN embryo_package p ON p.organization_id=i.organization_id AND p.id=i.package_id WHERE i.organization_id=? AND i.embryo_id=? AND i.removed_at IS NULL LIMIT 1)",
                (rs, row) ->
                    new State(
                        !rs.getBoolean(1) ? "FRESH" : rs.getBoolean(2) ? "THAWED" : "CRYOPRESERVED",
                        rs.getObject(3, UUID.class)),
                tenant,
                embryoId,
                tenant,
                embryoId,
                tenant,
                embryoId);
    return rows.getFirst();
  }

  @Override
  public Map<UUID, State> states(UUID tenant, Collection<UUID> embryoIds) {
    if (embryoIds.isEmpty()) return Map.of();
    var result = new java.util.HashMap<UUID, State>();
    jdbc.query(
        """
        SELECT e.id,
          EXISTS(SELECT 1 FROM cryopreservation_item c WHERE c.organization_id=e.organization_id AND c.embryo_id=e.id) frozen,
          EXISTS(SELECT 1 FROM thaw_event_item t WHERE t.organization_id=e.organization_id AND t.embryo_id=e.id) thawed,
          p.current_location_id
        FROM embryo e
        LEFT JOIN package_item i ON i.organization_id=e.organization_id AND i.embryo_id=e.id AND i.removed_at IS NULL
        LEFT JOIN embryo_package p ON p.organization_id=i.organization_id AND p.id=i.package_id
        WHERE e.organization_id=:tenant AND e.id IN (:ids)
        """,
        Map.of("tenant", tenant, "ids", embryoIds),
        rs -> {
          result.put(
              rs.getObject("id", UUID.class),
              new State(
                  !rs.getBoolean("frozen")
                      ? "FRESH"
                      : rs.getBoolean("thawed") ? "THAWED" : "CRYOPRESERVED",
                  rs.getObject("current_location_id", UUID.class)));
        });
    return Map.copyOf(result);
  }
}
