package com.bovina.transfer.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.embryology.domain.Embryo;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class FreshTransferInvariantTest {
  private static final StableIds IDS = new StableIds();

  @Test
  void transferRequiresAnExplicitReservationTransition() {
    var embryo = embryo();

    assertThatThrownBy(embryo::performTransfer)
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("EMBRYO_NOT_RESERVED");

    embryo.reserve();
    embryo.performTransfer();

    assertThat(embryo.availability()).isEqualTo(Embryo.AvailabilityStatus.TRANSFERRED);
    assertThatThrownBy(embryo::performTransfer)
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("EMBRYO_NOT_RESERVED");
  }

  @Test
  void reservationLifecycleIsExplicitAndTerminal() {
    var reservation =
        new TransferReservation(
            IDS.next(), IDS.next(), IDS.next(), IDS.next(), IDS.next(), Instant.EPOCH);

    reservation.cancel(IDS.next(), Instant.EPOCH.plusSeconds(1), "Recipient changed");

    assertThat(reservation.status()).isEqualTo(TransferReservation.Status.CANCELLED);
    assertThatThrownBy(() -> reservation.consume(IDS.next(), Instant.EPOCH.plusSeconds(2)))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("TRANSFER_RESERVATION_NOT_ACTIVE");
  }

  @Test
  void bulkReservationRejectsTheSameEmbryoTwice() {
    var embryo = IDS.next();

    assertThatThrownBy(
            () ->
                new TransferBatches.Reservation(
                    IDS.next(),
                    List.of(
                        new TransferBatches.ReservationItem(
                            IDS.next(), IDS.next(), embryo, IDS.next(), 0),
                        new TransferBatches.ReservationItem(
                            IDS.next(), IDS.next(), embryo, IDS.next(), 0))))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("DUPLICATE_EMBRYO");
  }

  @Test
  void performedDateUsesTheRecordedOperationalTimezone() {
    var transfer =
        new EmbryoTransfer(
            IDS.next(),
            IDS.next(),
            IDS.next(),
            IDS.next(),
            Instant.parse("2026-09-14T02:00:00Z"),
            "America/Sao_Paulo",
            EmbryoTransfer.Origin.FRESH,
            IDS.next(),
            null,
            DataProvenance.manual(IDS.next(), Instant.EPOCH));

    assertThat(transfer.performedOn()).isEqualTo(LocalDate.of(2026, 9, 13));
  }

  private Embryo embryo() {
    return new Embryo(
        IDS.next(),
        new Embryo.Registration(IDS.next(), IDS.next(), "E-1", null, Instant.EPOCH),
        DataProvenance.manual(IDS.next(), Instant.EPOCH));
  }
}
