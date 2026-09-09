package com.bovina.audit.infrastructure;

import com.bovina.audit.application.AuditEvent;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditStore {
  private final JdbcTemplate jdbc;

  public AuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void append(AuditEvent event) {
    jdbc.update(
        """
        INSERT INTO audit_event(id, organization_id, occurred_at, actor_id, actor_type, action,
            entity_type, entity_id, entity_version, reason, previous_state, new_state, correlation_id)
        VALUES (?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.id(),
        event.context().tenantId(),
        Timestamp.from(event.occurredAt()),
        event.context().actorId(),
        event.action(),
        event.entityType(),
        event.entityId(),
        event.entityVersion(),
        event.reason(),
        event.previousState(),
        event.newState(),
        event.context().correlationId());
  }
}
