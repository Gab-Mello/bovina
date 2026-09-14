package com.bovina.animals.application;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Bounded, tenant-scoped identity snapshots for performed donor procedures. */
@Service
public class DonorDirectory {
  private final NamedParameterJdbcTemplate jdbc;

  public DonorDirectory(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, Donor> snapshots(UUID tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    if (ids.size() > 100) throw new IllegalArgumentException("Snapshot page exceeds 100 donors");
    var parameters = Map.of("tenant", tenant, "ids", ids);
    var donors =
        jdbc.query(
            "SELECT id,name,sex,status,version FROM animal WHERE organization_id=:tenant AND id IN (:ids) ORDER BY id FOR SHARE",
            parameters,
            (rs, n) ->
                new Donor(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("sex"),
                    rs.getString("status"),
                    rs.getLong("version"),
                    List.of()));
    var identifiers = new HashMap<UUID, List<Identifier>>();
    jdbc.query(
        "SELECT animal_id,type,issuer,value FROM animal_identifier WHERE organization_id=:tenant AND animal_id IN (:ids) AND status='ACTIVE' ORDER BY animal_id,type,id",
        parameters,
        rs -> {
          identifiers
              .computeIfAbsent(rs.getObject("animal_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new Identifier(
                      rs.getString("type"), rs.getString("issuer"), rs.getString("value")));
        });
    var result = new HashMap<UUID, Donor>();
    donors.forEach(
        d ->
            result.put(
                d.id(),
                new Donor(
                    d.id(),
                    d.name(),
                    d.sex(),
                    d.status(),
                    d.version(),
                    identifiers.getOrDefault(d.id(), List.of()))));
    return Map.copyOf(result);
  }

  public record Identifier(String type, String issuer, String value) {}

  public record Donor(
      UUID id, String name, String sex, String status, long version, List<Identifier> identifiers) {
    public Donor {
      identifiers = List.copyOf(identifiers);
    }

    public boolean eligibleForNewCollection() {
      return sex.equals("FEMALE") && status.equals("ACTIVE");
    }
  }
}
