package com.bovina.compliance.application;

import com.bovina.compliance.domain.ComplianceRuleDefinition;
import com.bovina.compliance.domain.RecordedSemenFertilizationFields;
import com.bovina.compliance.domain.RecordedSemenFertilizationFields.Result;
import com.bovina.compliance.infrastructure.ComplianceRuleStore;
import com.bovina.compliance.infrastructure.MatingComplianceEvidence;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.platform.application.PageResult;
import com.bovina.platform.application.SearchPage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceEvaluations {
  private static final String SEMEN_FIELD_VALIDATOR = "RECORDED_SEMEN_FERTILIZATION_FIELDS";
  private final ComplianceRuleStore rules;
  private final MatingComplianceEvidence matings;
  private final TenantAccess access;
  private final Clock clock;
  private final RecordedSemenFertilizationFields semenFields =
      new RecordedSemenFertilizationFields();

  public ComplianceEvaluations(
      ComplianceRuleStore rules,
      MatingComplianceEvidence matings,
      TenantAccess access,
      Clock clock) {
    this.rules = rules;
    this.matings = matings;
    this.access = access;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Evaluation preview(ExecutionContext c, Preview request) {
    access.require(c, "compliance:read");
    if (!"MATING".equals(request.subjectType())
        || request.subjectId() == null
        || request.ruleKey() == null
        || !request.ruleKey().matches("[A-Z][A-Z0-9_]{0,79}")) throw rejected();
    var facts = matings.find(c.tenantId(), request.subjectId());
    if (facts == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.NOT_FOUND, "MATING_NOT_FOUND", "Mating not found");
    var factDate = facts.fertilizedAt().atZone(ZoneId.of(facts.zoneId())).toLocalDate();
    var rule = rules.applicable(request.ruleKey(), factDate);
    if (rule == null) {
      var known = rules.latest(request.ruleKey());
      return known == null
          ? result(request, null, Result.UNKNOWN, "RULE_SPEC_NOT_VERIFIED")
          : result(request, known, Result.NOT_APPLICABLE, "OUTSIDE_VERIFIED_EFFECTIVE_INTERVAL");
    }
    if (!"MATING".equals(rule.appliesTo()) || !SEMEN_FIELD_VALIDATOR.equals(rule.validatorKey()))
      return result(request, rule, Result.UNKNOWN, "VALIDATOR_NOT_IMPLEMENTED");
    var outcome =
        semenFields.evaluate(
            rule,
            factDate,
            facts.semenBatchCode(),
            facts.producerName(),
            facts.fertilizedAt() != null);
    return result(
        request,
        rule,
        outcome,
        outcome == Result.PASS
            ? "RECORDED_FIELDS_PRESENT_NOT_OVERALL_COMPLIANCE"
            : outcome == Result.FAIL
                ? "REQUIRED_RECORDED_FIELD_MISSING"
                : "OUTSIDE_VERIFIED_EFFECTIVE_INTERVAL");
  }

  @Transactional(readOnly = true)
  public PageResult<ComplianceRuleDefinition> definitions(ExecutionContext c, SearchPage page) {
    access.require(c, "compliance:read");
    return new PageResult<>(rules.page(page), page.page(), page.size());
  }

  private Evaluation result(
      Preview request, ComplianceRuleDefinition rule, Result outcome, String explanationCode) {
    return new Evaluation(
        request.ruleKey(),
        rule == null ? null : rule.versionLabel(),
        request.subjectType(),
        request.subjectId(),
        outcome,
        explanationCode,
        rule == null ? null : rule.sourceReference(),
        rule == null ? null : rule.article(),
        clock.instant());
  }

  public record Preview(String ruleKey, String subjectType, UUID subjectId) {}

  public record Evaluation(
      String ruleKey,
      String ruleVersion,
      String subjectType,
      UUID subjectId,
      Result result,
      String explanationCode,
      String sourceReference,
      String article,
      Instant evaluatedAt) {}

  private static ApplicationFailure rejected() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED,
        "INVALID_COMPLIANCE_PREVIEW",
        "Only a verified rule key and tenant-owned Mating are supported");
  }
}
