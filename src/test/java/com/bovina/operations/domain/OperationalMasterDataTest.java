package com.bovina.operations.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.*;
import org.junit.jupiter.api.Test;

class OperationalMasterDataTest {
  private final StableIds ids = new StableIds();

  @Test
  void deactivationPreservesLocationIdentityAndRejectsStaleVersion() {
    var location =
        new OperationalLocation(
            ids.next(),
            ids.next(),
            " Lab ",
            OperationalLocation.Type.LAB,
            "America/Sao_Paulo",
            "ACTIVE",
            3);
    var inactive = location.deactivate(3);
    assertThat(inactive.id()).isEqualTo(location.id());
    assertThat(inactive.establishmentId()).isEqualTo(location.establishmentId());
    assertThat(inactive.status()).isEqualTo("INACTIVE");
    assertThatThrownBy(() -> inactive.deactivate(3)).isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void rejectsInvalidTimezoneAndUnspecifiedProfessionalType() {
    assertThatThrownBy(
            () ->
                new OperationalLocation(
                    ids.next(),
                    ids.next(),
                    "Lab",
                    OperationalLocation.Type.LAB,
                    "Not/AZone",
                    "ACTIVE",
                    0))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new Professional(ids.next(), "Name", null, null, "ACTIVE", 0))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void endingAssignmentKeepsCredentialSnapshotAndCannotRewriteClosedHistory() {
    var start = LocalDate.of(2026, 1, 1);
    var assignment =
        new ResponsibleTechnicianAssignment(
            ids.next(),
            ids.next(),
            ids.next(),
            ids.next(),
            "Declared issuer",
            "SP",
            "123",
            null,
            new EffectivePeriod(start, null),
            0);
    var ended = assignment.end(start.plusMonths(1), 0);
    assertThat(ended.credentialNumber()).isEqualTo("123");
    assertThatThrownBy(() -> ended.end(start.plusMonths(2), 1))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ASSIGNMENT_ALREADY_ENDED");
    assertThatThrownBy(() -> assignment.end(null, 0)).isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> assignment.end(start, 0)).isInstanceOf(ApplicationFailure.class);
  }
}
