package com.bovina.compliance.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatingComplianceEvidence {
  private final JdbcTemplate jdbc;

  public MatingComplianceEvidence(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Facts find(UUID tenant, UUID matingId) {
    var rows =
        jdbc.query(
            """
            SELECT m.id,m.fertilized_at,s.batch_code,p.name,opu.timezone
            FROM mating m
            JOIN semen_batch s ON s.organization_id=m.organization_id AND s.id=m.semen_batch_id
            JOIN external_establishment_reference p
              ON p.organization_id=s.organization_id AND p.id=s.producer_establishment_id
            JOIN oocyte_collection c
              ON c.organization_id=m.organization_id AND c.id=m.oocyte_collection_id
            JOIN opu_session opu
              ON opu.organization_id=c.organization_id AND opu.id=c.opu_session_id
            WHERE m.organization_id=? AND m.id=?
            """,
            (rs, n) ->
                new Facts(
                    rs.getObject(1, UUID.class),
                    rs.getTimestamp(2).toInstant(),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5)),
            tenant,
            matingId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public record Facts(
      UUID matingId,
      Instant fertilizedAt,
      String semenBatchCode,
      String producerName,
      String zoneId) {}
}
