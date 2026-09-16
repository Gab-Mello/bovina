package com.bovina.transfer.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.util.*;

public record PregnancyCheck(
    UUID id,
    UUID transferId,
    Instant checkedAt,
    String timezone,
    Result result,
    String methodCode,
    String observations,
    UUID professionalId,
    UUID supersedesCheckId,
    String correctionReason,
    DataProvenance provenance) {
  public PregnancyCheck {
    StableIds.requireVersion7(id);
    if (transferId == null
        || checkedAt == null
        || result == null
        || professionalId == null
        || provenance == null) throw rejected("INVALID_PREGNANCY_CHECK");
    try {
      if (timezone == null || timezone.length() > 64) throw new DateTimeException("timezone");
      ZoneId.of(timezone);
    } catch (DateTimeException e) {
      throw rejected("INVALID_CHECK_TIMEZONE");
    }
    methodCode = code(methodCode);
    observations = optionalText(observations, 2000, "INVALID_CHECK_OBSERVATIONS");
    correctionReason = optionalText(correctionReason, 500, "INVALID_CORRECTION_REASON");
    if ((supersedesCheckId == null) != (correctionReason == null))
      throw rejected("INVALID_CHECK_CORRECTION");
  }

  public LocalDate checkedOn() {
    return checkedAt.atZone(ZoneId.of(timezone)).toLocalDate();
  }

  public enum Result {
    PREGNANT,
    NOT_PREGNANT,
    INCONCLUSIVE,
    PREGNANCY_LOSS
  }

  private static String code(String value) {
    if (value == null
        || !(value = value.strip().toUpperCase(Locale.ROOT)).matches("[A-Z][A-Z0-9_]{0,47}"))
      throw rejected("INVALID_CHECK_METHOD");
    return value;
  }

  private static String optionalText(String value, int max, String error) {
    if (value == null) return null;
    if (value.isBlank() || value.length() > max) throw rejected(error);
    return value.strip();
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Pregnancy check is invalid");
  }
}
