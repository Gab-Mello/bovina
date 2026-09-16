package com.bovina.compliance.domain;

import java.time.LocalDate;

/** Art. 28 IV-V-VI field-presence check, not a general CPIVE compliance determination. */
public final class RecordedSemenFertilizationFields {

  public Result evaluate(
      ComplianceRuleDefinition rule,
      LocalDate factDate,
      String semenBatchCode,
      String producerName,
      boolean fertilizationDateRecorded) {
    if (!rule.appliesOn(factDate)) return Result.NOT_APPLICABLE;
    if (semenBatchCode == null
        || semenBatchCode.isBlank()
        || producerName == null
        || producerName.isBlank()
        || !fertilizationDateRecorded) return Result.FAIL;
    return Result.PASS;
  }

  public enum Result {
    PASS,
    FAIL,
    NOT_APPLICABLE,
    UNKNOWN
  }
}
