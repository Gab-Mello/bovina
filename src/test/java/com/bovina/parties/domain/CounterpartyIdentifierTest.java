package com.bovina.parties.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import org.junit.jupiter.api.Test;

class CounterpartyIdentifierTest {
  @Test
  void preservesIssuerSpecificFormattingAndRejectsMissingEvidence() {
    var id = new StableIds().next();
    var identifier = new CounterpartyIdentifier(id, "EXTERNAL_ID", "Registry", " 001-a/2 ");
    assertThat(identifier.normalizedValue()).isEqualTo("001-a/2");
    assertThatThrownBy(() -> new CounterpartyIdentifier(id, "EXTERNAL_ID", null, " "))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new CounterpartyIdentifier(id, "arbitrary type", null, "1"))
        .isInstanceOf(ApplicationFailure.class);
  }
}
