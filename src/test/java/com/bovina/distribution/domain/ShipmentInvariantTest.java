package com.bovina.distribution.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ShipmentInvariantTest {
  private final StableIds ids = new StableIds();

  @Test
  void performedDispatchCannotBeCancelledOrDispatchedAgain() {
    var shipment =
        new Shipment(
            ids.next(),
            ids.next(),
            ids.next(),
            ids.next(),
            null,
            "MOVEMENT",
            ids.next(),
            Instant.EPOCH);
    shipment.dispatch(0);
    assertThat(shipment.status()).isEqualTo(Shipment.Status.SHIPPED);
    assertThatThrownBy(() -> shipment.cancel(0)).isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> shipment.dispatch(0)).isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void staleDraftCannotBeDispatched() {
    var shipment =
        new Shipment(
            ids.next(),
            ids.next(),
            ids.next(),
            ids.next(),
            null,
            "MOVEMENT",
            ids.next(),
            Instant.EPOCH);
    assertThatThrownBy(() -> shipment.dispatch(1)).isInstanceOf(ApplicationFailure.class);
    assertThat(shipment.status()).isEqualTo(Shipment.Status.DRAFT);
  }
}
