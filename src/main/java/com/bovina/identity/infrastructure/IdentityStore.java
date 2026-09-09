package com.bovina.identity.infrastructure;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.MembershipView;
import com.bovina.identity.domain.MembershipRole;
import com.bovina.identity.domain.Organization;
import com.bovina.identity.domain.OrganizationMembership;
import com.bovina.platform.application.ApplicationFailure;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityStore {
  private final JdbcTemplate jdbc;
  private final EntityManager entities;

  public IdentityStore(JdbcTemplate jdbc, EntityManager entities) {
    this.jdbc = jdbc;
    this.entities = entities;
  }

  public UUID registerUser(UUID proposedId, AuthenticatedIdentity identity, Instant now) {
    jdbc.update(
        """
        INSERT INTO user_account(id, issuer, subject, status, created_at)
        VALUES (?, ?, ?, 'ACTIVE', ?) ON CONFLICT (issuer, subject) DO NOTHING
        """,
        proposedId,
        identity.issuer(),
        identity.subject(),
        Timestamp.from(now));
    return jdbc
        .query(
            "SELECT id FROM user_account WHERE issuer=? AND subject=? AND status='ACTIVE'",
            (rs, row) -> rs.getObject(1, UUID.class),
            identity.issuer(),
            identity.subject())
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.FORBIDDEN,
                    "ACCOUNT_DISABLED",
                    "Account is not available for membership"));
  }

  public void createOrganization(Organization organization, OrganizationMembership membership) {
    entities.persist(organization);
    entities.persist(membership);
    entities.flush();
  }

  public void createMembership(OrganizationMembership membership) {
    entities.persist(membership);
    entities.flush();
  }

  public Optional<OrganizationMembership> membership(UUID tenant, UUID id) {
    return entities
        .createQuery(
            "select m from OrganizationMembership m where m.organizationId=:tenant and m.id=:id",
            OrganizationMembership.class)
        .setParameter("tenant", tenant)
        .setParameter("id", id)
        .getResultStream()
        .findFirst();
  }

  public void flush() {
    entities.flush();
  }

  public List<MembershipView> memberships(
      AuthenticatedIdentity identity, UUID selected, Instant now, int limit) {
    return jdbc.query(
        """
        SELECT m.organization_id, u.id, m.role
        FROM user_account u JOIN organization_membership m ON m.user_account_id=u.id
        JOIN organization o ON o.id=m.organization_id
        WHERE u.issuer=? AND u.subject=? AND u.status='ACTIVE' AND m.status='ACTIVE'
          AND o.status='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?)
        """
            + (selected == null ? "" : " AND m.organization_id=?")
            + " ORDER BY m.organization_id LIMIT ?",
        (rs, n) ->
            new MembershipView(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                MembershipRole.valueOf(rs.getString(3)).permissions()),
        selected == null
            ? new Object[] {
              identity.issuer(), identity.subject(), Timestamp.from(now), Timestamp.from(now), limit
            }
            : new Object[] {
              identity.issuer(),
              identity.subject(),
              Timestamp.from(now),
              Timestamp.from(now),
              selected,
              limit
            });
  }

  public boolean allows(UUID tenant, UUID actor, String permission, Instant now) {
    return jdbc
        .query(
            """
        SELECT m.role FROM organization_membership m
        JOIN user_account u ON u.id=m.user_account_id JOIN organization o ON o.id=m.organization_id
        WHERE m.organization_id=? AND m.user_account_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
          AND o.status='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?)
        """,
            (rs, n) -> MembershipRole.valueOf(rs.getString(1)).permissions().contains(permission),
            tenant,
            actor,
            Timestamp.from(now),
            Timestamp.from(now))
        .stream()
        .anyMatch(Boolean.TRUE::equals);
  }
}
