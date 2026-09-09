package com.bovina.parties.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.parties.application.CreateClient;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClientInvariantTest {
  private final StableIds ids = new StableIds();

  @Test
  void nameInvariantDoesNotDependOnBeanValidation() {
    var now = Instant.now();
    assertThatThrownBy(
            () ->
                Party.registerClient(
                    ids.next(),
                    ids.next(),
                    ClientType.PERSON,
                    " ",
                    now,
                    DataProvenance.manual(ids.next(), now)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CreateClient(
                    ids.next(),
                    ClientType.PERSON,
                    "X".repeat(201),
                    new CommandMetadata(ids.next(), now)))
        .isInstanceOf(ApplicationFailure.class);
  }
}
