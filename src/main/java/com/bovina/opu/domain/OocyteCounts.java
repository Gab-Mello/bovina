package com.bovina.opu.domain;

import com.bovina.platform.application.ApplicationFailure;

public record OocyteCounts(int totalRecovered, int viable, Integer folliclesAspirated) {
  public OocyteCounts {
    if (totalRecovered < 0
        || viable < 0
        || viable > totalRecovered
        || (folliclesAspirated != null && folliclesAspirated < 0))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_OOCYTE_COUNTS",
          "Counts must be nonnegative and viable cannot exceed total recovered");
  }

  public long availableAfter(long confirmedAllocation) {
    if (confirmedAllocation < 0 || confirmedAllocation > viable)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "OOCYTE_ALLOCATION_EXCEEDS_VIABLE_COUNT",
          "Confirmed allocations exceed the viable count");
    return viable - confirmedAllocation;
  }

  public void requireAllocation(long confirmedAllocation, int requested) {
    if (requested <= 0 || requested > availableAfter(confirmedAllocation))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "OOCYTE_ALLOCATION_EXCEEDS_VIABLE_COUNT",
          "Requested allocation exceeds available oocytes");
  }
}
