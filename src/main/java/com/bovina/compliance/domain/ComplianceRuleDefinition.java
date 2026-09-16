package com.bovina.compliance.domain;

import java.time.LocalDate;

public record ComplianceRuleDefinition(
    String ruleKey,
    String versionLabel,
    String authority,
    String sourceReference,
    String sourceUrl,
    String article,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String appliesTo,
    String severity,
    String validatorKey) {
  public boolean appliesOn(LocalDate date) {
    return !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo));
  }
}
