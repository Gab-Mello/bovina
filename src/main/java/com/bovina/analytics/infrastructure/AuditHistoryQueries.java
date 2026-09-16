package com.bovina.analytics.infrastructure;

import com.bovina.analytics.application.OperationalAudit.Entry;
import com.bovina.platform.application.SearchPage;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditHistoryQueries {
  private final JdbcTemplate jdbc;

  public AuditHistoryQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Entry> search(UUID tenant, String type, UUID entity, SearchPage page) {
    return jdbc.query(
        """
        SELECT id,occurred_at,actor_id,action,entity_type,entity_id,entity_version,reason,correlation_id
          FROM audit_event WHERE organization_id=?
          AND (?::text IS NULL OR entity_type=?) AND (?::uuid IS NULL OR entity_id=?)
          ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?
        """,
        (rs, n) ->
            new Entry(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getObject("entity_version", Long.class),
                rs.getString("reason"),
                rs.getObject("correlation_id", UUID.class)),
        tenant,
        type,
        type,
        entity,
        entity,
        page.size(),
        page.offset());
  }
}
