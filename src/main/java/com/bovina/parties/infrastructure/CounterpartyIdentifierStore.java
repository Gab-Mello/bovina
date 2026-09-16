package com.bovina.parties.infrastructure;

import com.bovina.parties.domain.CounterpartyIdentifier;
import com.bovina.platform.application.SearchPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CounterpartyIdentifierStore {
  private final JdbcTemplate jdbc;

  public CounterpartyIdentifierStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      UUID tenant, UUID counterparty, CounterpartyIdentifier i, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO party_identifier(id,organization_id,party_id,type,issuer,value,normalized_value,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?)",
        i.id(),
        tenant,
        counterparty,
        i.type(),
        i.issuer(),
        i.value(),
        i.normalizedValue(),
        actor,
        Timestamp.from(now));
  }

  public List<CounterpartyIdentifier> list(UUID tenant, UUID counterparty, SearchPage page) {
    return jdbc.query(
        "SELECT id,type,issuer,value FROM party_identifier WHERE organization_id=? AND party_id=? ORDER BY recorded_at,id LIMIT ? OFFSET ?",
        (rs, n) ->
            new CounterpartyIdentifier(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("issuer"),
                rs.getString("value")),
        tenant,
        counterparty,
        page.size(),
        page.offset());
  }
}
