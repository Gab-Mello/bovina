package com.bovina.analytics.infrastructure;

import com.bovina.analytics.application.DataQuality.Issue;
import com.bovina.platform.application.SearchPage;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataQualityQueries {
  private final NamedParameterJdbcTemplate jdbc;

  public DataQualityQueries(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Issue> search(UUID tenant, SearchPage page) {
    // Observed data gaps are not clinical eligibility or an unverified regulatory requirement.
    return jdbc.query(
        """
        WITH issues AS (
          SELECT 'EMBRYO' subject_type,e.id subject_id,'ASSESSMENT_NOT_RECORDED' code,'INFO' severity
            FROM embryo e WHERE e.organization_id=:tenant
            AND e.availability_status IN ('AVAILABLE','RESERVED')
            AND NOT EXISTS (SELECT 1 FROM embryo_evaluation a WHERE a.organization_id=e.organization_id AND a.embryo_id=e.id)
          UNION ALL
          SELECT 'PACKAGE',p.id,'INVENTORY_PROJECTION_MISMATCH','ERROR'
            FROM embryo_package p
            LEFT JOIN LATERAL (SELECT sequence,to_location_id FROM inventory_movement m
              WHERE m.organization_id=p.organization_id AND m.package_id=p.id ORDER BY sequence DESC LIMIT 1) last ON true
            WHERE p.organization_id=:tenant AND (p.current_location_id IS DISTINCT FROM last.to_location_id
              OR p.last_movement_sequence<>coalesce(last.sequence,0))
        ) SELECT * FROM issues WHERE code ILIKE :pattern OR subject_id::text ILIKE :pattern
          ORDER BY subject_type,subject_id,code LIMIT :limit OFFSET :offset
        """,
        Map.of(
            "tenant",
            tenant,
            "pattern",
            page.pattern(),
            "limit",
            page.size(),
            "offset",
            page.offset()),
        (rs, n) ->
            new Issue(
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("code"),
                rs.getString("severity")));
  }
}
