package com.bovina.transfer.application;

import com.bovina.platform.application.ApplicationFailure;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bovina.outcomes")
public record OutcomeWindows(Window d30, Window d60) {
  public OutcomeWindows {
    if (d30 == null || d60 == null || d30.targetDay() >= d60.targetDay())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_OUTCOME_WINDOWS",
          "Outcome analysis windows are invalid");
  }

  public Window cohort(Cohort cohort) {
    return cohort == Cohort.D30 ? d30 : d60;
  }

  public enum Cohort {
    D30,
    D60
  }

  public record Window(int targetDay, int fromDay, int throughDay) {
    public Window {
      if (targetDay < 1 || fromDay < 0 || fromDay > targetDay || throughDay < targetDay)
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_OUTCOME_WINDOW",
            "Outcome analysis window is invalid");
    }
  }
}
