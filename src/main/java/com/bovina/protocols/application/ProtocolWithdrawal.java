package com.bovina.protocols.application;

import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.UUID;

public record ProtocolWithdrawal(
    UUID versionId, String reason, UUID withdrawnBy, Instant withdrawnAt) {
  public ProtocolWithdrawal {
    if (versionId == null
        || reason == null
        || reason.isBlank()
        || reason.length() > 500
        || withdrawnBy == null
        || withdrawnAt == null)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_PROTOCOL_WITHDRAWAL",
          "Withdrawal requires a reason and attribution");
    reason = reason.strip();
  }
}
