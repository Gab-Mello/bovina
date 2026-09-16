package com.bovina.traceability.infrastructure;

import com.bovina.platform.application.SearchPage;
import com.bovina.traceability.application.EmbryoTraceability.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TraceabilityQueries {
  private final NamedParameterJdbcTemplate jdbc;

  public TraceabilityQueries(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Lineage lineage(UUID tenant, UUID embryo) {
    var rows =
        jdbc.query(
            """
        SELECT e.id,e.human_code,e.mating_id,m.oocyte_collection_id,c.donor_id,c.opu_session_id,
          o.farm_property_id,m.semen_batch_id,s.sire_id,s.producer_establishment_id,
          p.id package_id,p.current_location_id,t.id transfer_id,t.recipient_cycle_id,r.recipient_animal_id
        FROM embryo e
        JOIN mating m ON m.organization_id=e.organization_id AND m.id=e.mating_id
        JOIN oocyte_collection c ON c.organization_id=m.organization_id AND c.id=m.oocyte_collection_id
        JOIN opu_session o ON o.organization_id=c.organization_id AND o.id=c.opu_session_id
        JOIN semen_batch s ON s.organization_id=m.organization_id AND s.id=m.semen_batch_id
        LEFT JOIN package_item i ON i.organization_id=e.organization_id AND i.embryo_id=e.id AND i.removed_at IS NULL
        LEFT JOIN embryo_package p ON p.organization_id=i.organization_id AND p.id=i.package_id
        LEFT JOIN embryo_transfer t ON t.organization_id=e.organization_id AND t.embryo_id=e.id
        LEFT JOIN recipient_cycle r ON r.organization_id=t.organization_id AND r.id=t.recipient_cycle_id
        WHERE e.organization_id=:tenant AND e.id=:embryo
        """,
            Map.of("tenant", tenant, "embryo", embryo),
            (rs, n) ->
                new Lineage(
                    rs.getObject("id", UUID.class),
                    rs.getString("human_code"),
                    rs.getObject("mating_id", UUID.class),
                    rs.getObject("oocyte_collection_id", UUID.class),
                    rs.getObject("donor_id", UUID.class),
                    rs.getObject("opu_session_id", UUID.class),
                    rs.getObject("farm_property_id", UUID.class),
                    rs.getObject("semen_batch_id", UUID.class),
                    rs.getObject("sire_id", UUID.class),
                    rs.getObject("producer_establishment_id", UUID.class),
                    rs.getObject("package_id", UUID.class),
                    rs.getObject("current_location_id", UUID.class),
                    rs.getObject("transfer_id", UUID.class),
                    rs.getObject("recipient_cycle_id", UUID.class),
                    rs.getObject("recipient_animal_id", UUID.class)));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<TimelineEntry> timeline(UUID tenant, UUID embryo, SearchPage page) {
    return jdbc.query(
        """
        WITH memberships AS (
          SELECT * FROM package_item WHERE organization_id=:tenant AND embryo_id=:embryo
        ), transfers AS (
          SELECT * FROM embryo_transfer WHERE organization_id=:tenant AND embryo_id=:embryo
        ), events AS (
          SELECT id,'IDENTIFIED' type,identified_at occurred_at,recorded_at,mating_id related_id
            FROM embryo WHERE organization_id=:tenant AND id=:embryo
          UNION ALL
          SELECT id,'EVALUATED',evaluated_at,recorded_at,supersedes_evaluation_id
            FROM embryo_evaluation WHERE organization_id=:tenant AND embryo_id=:embryo
          UNION ALL
          SELECT c.id,'CRYOPRESERVED',c.occurred_at,c.recorded_at,i.id
            FROM cryopreservation_item i JOIN cryopreservation_event c ON c.organization_id=i.organization_id AND c.id=i.event_id
            WHERE i.organization_id=:tenant AND i.embryo_id=:embryo
          UNION ALL
          SELECT id,'PACKAGED',added_at,added_at,package_id FROM memberships
          UNION ALL
          SELECT im.id,im.movement_type,im.occurred_at,im.recorded_at,im.package_id
            FROM inventory_movement im WHERE im.organization_id=:tenant AND EXISTS (
              SELECT 1 FROM memberships i WHERE i.package_id=im.package_id
                AND i.added_at<=im.recorded_at AND (i.removed_at IS NULL OR i.removed_at>=im.recorded_at))
          UNION ALL
          SELECT t.id,'THAWED',t.occurred_at,t.recorded_at,t.package_id
            FROM thaw_event t JOIN thaw_event_item i ON i.organization_id=t.organization_id AND i.thaw_event_id=t.id
            WHERE i.organization_id=:tenant AND i.embryo_id=:embryo
          UNION ALL
          SELECT id,'TRANSFERRED',performed_at,recorded_at,recipient_cycle_id FROM transfers
          UNION ALL
          SELECT p.id,'PREGNANCY_CHECK',p.checked_at,p.recorded_at,p.supersedes_check_id
            FROM pregnancy_check p JOIN transfers t ON t.id=p.transfer_id AND t.organization_id=p.organization_id
          UNION ALL
          SELECT i.id,'CHECK_INVALIDATED',i.invalidated_at,i.invalidated_at,i.pregnancy_check_id
            FROM pregnancy_check_invalidation i JOIN transfers t ON t.id=i.transfer_id AND t.organization_id=i.organization_id
        ) SELECT * FROM events ORDER BY occurred_at,recorded_at,id,type LIMIT :limit OFFSET :offset
        """,
        Map.of("tenant", tenant, "embryo", embryo, "limit", page.size(), "offset", page.offset()),
        (rs, n) ->
            new TimelineEntry(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("related_id", UUID.class)));
  }
}
