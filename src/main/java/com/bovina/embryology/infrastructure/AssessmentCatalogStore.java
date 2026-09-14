package com.bovina.embryology.infrastructure;

import com.bovina.embryology.domain.AssessmentCatalog.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssessmentCatalogStore {
  private final JdbcTemplate jdbc;

  public AssessmentCatalogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertScheme(UUID tenant, Scheme scheme) {
    jdbc.update(
        "INSERT INTO assessment_scheme(id,organization_id,code,name,status) VALUES (?,?,?,?,?)",
        scheme.id(),
        tenant,
        scheme.code(),
        scheme.name(),
        scheme.status());
  }

  public Optional<Scheme> scheme(UUID tenant, UUID id) {
    return jdbc
        .query(
            "SELECT id,code,name,status FROM assessment_scheme WHERE organization_id=? AND id=?",
            (rs, n) ->
                new Scheme(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            tenant,
            id)
        .stream()
        .findFirst();
  }

  public void insertVersion(UUID tenant, Version version) {
    jdbc.update(
        "INSERT INTO assessment_scheme_version(id,organization_id,scheme_id,version_label,effective_from,status,published_by,published_at) VALUES (?,?,?,?,?,?,?,?)",
        version.id(),
        tenant,
        version.schemeId(),
        version.versionLabel(),
        version.effectiveFrom(),
        version.status(),
        version.publishedBy(),
        Timestamp.from(version.publishedAt()));
    jdbc.batchUpdate(
        "INSERT INTO assessment_code(id,organization_id,scheme_version_id,dimension,code,display_name,sort_order) VALUES (?,?,?,?,?,?,?)",
        version.codes(),
        200,
        (ps, code) -> {
          ps.setObject(1, code.id());
          ps.setObject(2, tenant);
          ps.setObject(3, version.id());
          ps.setString(4, code.dimension().name());
          ps.setString(5, code.code());
          ps.setString(6, code.displayName());
          ps.setInt(7, code.sortOrder());
        });
  }

  public Optional<Version> version(UUID tenant, UUID id, boolean lock) {
    var head =
        jdbc
            .query(
                "SELECT id,scheme_id,version_label,effective_from,status,published_by,published_at FROM assessment_scheme_version WHERE organization_id=? AND id=?"
                    + (lock ? " FOR SHARE" : ""),
                (rs, n) ->
                    new Object[] {
                      rs.getObject(1, UUID.class),
                      rs.getObject(2, UUID.class),
                      rs.getString(3),
                      rs.getObject(4, java.time.LocalDate.class),
                      rs.getString(5),
                      rs.getObject(6, UUID.class),
                      rs.getTimestamp(7).toInstant()
                    },
                tenant,
                id)
            .stream()
            .findFirst();
    if (head.isEmpty()) return Optional.empty();
    var h = head.get();
    var codes =
        jdbc.query(
            "SELECT id,scheme_version_id,dimension,code,display_name,sort_order FROM assessment_code WHERE organization_id=? AND scheme_version_id=? ORDER BY sort_order,id",
            (rs, n) ->
                new Code(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    Dimension.valueOf(rs.getString(3)),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6)),
            tenant,
            id);
    return Optional.of(
        new Version(
            (UUID) h[0],
            (UUID) h[1],
            (String) h[2],
            (java.time.LocalDate) h[3],
            (String) h[4],
            (UUID) h[5],
            (java.time.Instant) h[6],
            codes));
  }

  public Map<UUID, Code> codes(UUID tenant, UUID version, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    var parameters = String.join(",", Collections.nCopies(ids.size(), "?"));
    var args = new ArrayList<Object>();
    args.add(tenant);
    args.add(version);
    args.addAll(ids);
    var result =
        jdbc.query(
            "SELECT id,scheme_version_id,dimension,code,display_name,sort_order FROM assessment_code WHERE organization_id=? AND scheme_version_id=? AND id IN ("
                + parameters
                + ")",
            (rs, n) ->
                new Code(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    Dimension.valueOf(rs.getString(3)),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6)),
            args.toArray());
    var map = new HashMap<UUID, Code>();
    result.forEach(c -> map.put(c.id(), c));
    return map;
  }
}
