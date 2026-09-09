package com.bovina.parties.infrastructure;

import com.bovina.parties.application.CounterpartyView;
import com.bovina.parties.domain.*;
import com.bovina.platform.application.SearchPage;
import com.bovina.platform.domain.Address;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CounterpartyQueries {
  private final JdbcTemplate jdbc;

  public CounterpartyQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean hasRole(UUID tenant, UUID id, CounterpartyRole role) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM party_role WHERE organization_id=? AND party_id=? AND role=?)",
            Boolean.class,
            tenant,
            id,
            role.name()));
  }

  public List<CounterpartyView> search(UUID tenant, CounterpartyRole role, SearchPage page) {
    return jdbc.query(
        """
        SELECT p.* FROM party p WHERE p.organization_id=?
        AND EXISTS (SELECT 1 FROM party_role r WHERE r.organization_id=p.organization_id AND r.party_id=p.id AND r.role=?)
        AND (p.display_name ILIKE ? OR EXISTS (SELECT 1 FROM party_identifier i
             WHERE i.organization_id=p.organization_id AND i.party_id=p.id AND i.normalized_value ILIKE ?))
        ORDER BY lower(p.display_name),p.id LIMIT ? OFFSET ?
        """,
        (rs, n) ->
            new CounterpartyView(
                rs.getObject("id", UUID.class),
                ClientType.valueOf(rs.getString("type")),
                rs.getString("display_name"),
                rs.getString("legal_name"),
                rs.getString("address_line") == null
                    ? null
                    : new Address(
                        rs.getString("address_line"),
                        rs.getString("municipality"),
                        rs.getString("state"),
                        rs.getString("country"),
                        rs.getString("postal_code")),
                rs.getString("status"),
                rs.getLong("version")),
        tenant,
        role.name(),
        page.pattern(),
        page.pattern(),
        page.size(),
        page.offset());
  }
}
