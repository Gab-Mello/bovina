package com.bovina.platform.domain;

import com.bovina.platform.application.ApplicationFailure;
import java.time.LocalDate;

/** Half-open civil-date interval [from, until); null until means unbounded. */
public record EffectivePeriod(LocalDate from, LocalDate until) {
  public EffectivePeriod {
    if (from == null || (until != null && !until.isAfter(from)))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_EFFECTIVE_PERIOD",
          "An effective period requires a start and an optional later end");
  }

  public boolean overlaps(EffectivePeriod other) {
    return (until == null || other.from.isBefore(until))
        && (other.until == null || from.isBefore(other.until));
  }
}
