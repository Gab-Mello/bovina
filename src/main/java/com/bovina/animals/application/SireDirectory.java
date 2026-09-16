package com.bovina.animals.application;

import com.bovina.platform.application.ApplicationFailure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SireDirectory {
  private final JdbcTemplate jdbc;

  public SireDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Sire snapshot(UUID tenant, UUID id) {
    var sire =
        jdbc
            .query(
                "SELECT id,name,sex,status,version FROM animal WHERE organization_id=? AND id=? FOR SHARE",
                (rs, n) ->
                    new Sire(
                        id,
                        rs.getString("name"),
                        rs.getString("sex"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        List.of()),
                tenant,
                id)
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ApplicationFailure(
                        ApplicationFailure.Kind.NOT_FOUND, "SIRE_NOT_FOUND", "Sire not found"));
    if (!sire.sex().equals("MALE") || !sire.status().equals("ACTIVE"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "SIRE_NOT_ELIGIBLE",
          "Sire must be an active male animal");
    var identifiers =
        jdbc.query(
            "SELECT type,issuer,value FROM animal_identifier WHERE organization_id=? AND animal_id=? AND status='ACTIVE' ORDER BY type,id",
            (rs, n) -> new Identifier(rs.getString(1), rs.getString(2), rs.getString(3)),
            tenant,
            id);
    return new Sire(sire.id(), sire.name(), sire.sex(), sire.status(), sire.version(), identifiers);
  }

  public record Identifier(String type, String issuer, String value) {}

  public record Sire(
      UUID id, String name, String sex, String status, long version, List<Identifier> identifiers) {
    public Sire {
      identifiers = List.copyOf(identifiers);
    }
  }
}
