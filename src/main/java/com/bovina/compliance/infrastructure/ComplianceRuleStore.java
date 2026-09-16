package com.bovina.compliance.infrastructure;

import com.bovina.compliance.domain.ComplianceRuleDefinition;
import com.bovina.platform.application.SearchPage;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ComplianceRuleStore {
  private static final RowMapper<ComplianceRuleDefinition> ROW =
      (rs, n) ->
          new ComplianceRuleDefinition(
              rs.getString("rule_key"),
              rs.getString("version_label"),
              rs.getString("authority"),
              rs.getString("source_reference"),
              rs.getString("source_url"),
              rs.getString("article"),
              rs.getDate("effective_from").toLocalDate(),
              rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
              rs.getString("applies_to"),
              rs.getString("severity"),
              rs.getString("validator_key"));

  private final JdbcTemplate jdbc;

  public ComplianceRuleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public ComplianceRuleDefinition applicable(String key, LocalDate factDate) {
    var rows =
        jdbc.query(
            "SELECT * FROM compliance_rule_definition WHERE rule_key=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) ORDER BY effective_from DESC LIMIT 1",
            ROW,
            key,
            factDate,
            factDate);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public ComplianceRuleDefinition latest(String key) {
    var rows =
        jdbc.query(
            "SELECT * FROM compliance_rule_definition WHERE rule_key=? ORDER BY effective_from DESC LIMIT 1",
            ROW,
            key);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<ComplianceRuleDefinition> page(SearchPage page) {
    return jdbc.query(
        "SELECT * FROM compliance_rule_definition ORDER BY rule_key, effective_from DESC LIMIT ? OFFSET ?",
        ROW,
        page.size(),
        page.offset());
  }
}
