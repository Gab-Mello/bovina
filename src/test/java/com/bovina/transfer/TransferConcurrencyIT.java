package com.bovina.transfer;

import static com.bovina.support.fixture.TransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransferConcurrencyIT extends AuthenticatedIntegrationTest {
  @Test
  void competingBulkReservationsLockEmbryosInOrderAndCommitOnlyOneCompleteBatch() throws Exception {
    var production = ProductionFixtures.freshEmbryos(api, tenant("Transfer Concurrency Lab"), 2);
    var firstRecipient =
        ProductionFixtures.animal(api, production.tenant().id(), "FEMALE", "Recipient A");
    var secondRecipient =
        ProductionFixtures.animal(api, production.tenant().id(), "FEMALE", "Recipient B");
    var firstCycle =
        openCycle(api, production.tenant().id(), firstRecipient, LocalDate.of(2026, 9, 1));
    var secondCycle =
        openCycle(api, production.tenant().id(), secondRecipient, LocalDate.of(2026, 9, 1));
    var firstReservations = List.of(id(), id());
    var secondReservations = List.of(id(), id());
    var firstBatch = id();
    var secondBatch = id();
    var firstIntent =
        reservationBatch(
            firstBatch,
            List.of(
                reservationItem(
                    firstReservations.get(0), production.embryos().get(0), firstCycle, 0),
                reservationItem(
                    firstReservations.get(1), production.embryos().get(1), secondCycle, 0)));
    var reverseOrderIntent =
        reservationBatch(
            secondBatch,
            List.of(
                reservationItem(
                    secondReservations.get(0), production.embryos().get(1), firstCycle, 0),
                reservationItem(
                    secondReservations.get(1), production.embryos().get(0), secondCycle, 0)));

    var responses =
        concurrent(
            () ->
                api.post(
                    production.tenant().id(), "/transfers:bulk-reserve", firstBatch, firstIntent),
            () ->
                api.post(
                    production.tenant().id(),
                    "/transfers:bulk-reserve",
                    secondBatch,
                    reverseOrderIntent));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    var winningReservations =
        responses.getFirst().statusCode() == 200 ? firstReservations : secondReservations;
    var losingReservations =
        responses.getFirst().statusCode() == 200 ? secondReservations : firstReservations;
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer_reservation WHERE organization_id=? AND status='ACTIVE'",
                Integer.class,
                production.tenant().id()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer_reservation WHERE id IN (?,?)",
                Integer.class,
                losingReservations.get(0),
                losingReservations.get(1)))
        .isZero();

    assertConcurrentPerformCommitsOneTransfer(production, winningReservations.getFirst());
    assertCancelPerformRaceKeepsReservationAndEmbryoConsistent(
        production, winningReservations.get(1));
  }

  private void assertConcurrentPerformCommitsOneTransfer(
      ProductionFixtures.EmbryoProduction production, UUID reservation) throws Exception {
    var reference = reservationRef(reservation);
    var firstBatch = id();
    var secondBatch = id();
    var responses =
        concurrent(
            () ->
                api.post(
                    production.tenant().id(),
                    "/transfers:bulk-perform",
                    firstBatch,
                    performanceBatch(
                        firstBatch,
                        id(),
                        reservation,
                        1,
                        production.professional(),
                        Instant.parse("2026-09-01T12:00:00Z"))),
            () ->
                api.post(
                    production.tenant().id(),
                    "/transfers:bulk-perform",
                    secondBatch,
                    performanceBatch(
                        secondBatch,
                        id(),
                        reservation,
                        1,
                        production.professional(),
                        Instant.parse("2026-09-01T12:00:00Z"))));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer WHERE embryo_id=?",
                Integer.class,
                reference.embryo()))
        .isEqualTo(1);
  }

  private void assertCancelPerformRaceKeepsReservationAndEmbryoConsistent(
      ProductionFixtures.EmbryoProduction production, UUID originalReservation) throws Exception {
    var reference = reservationRef(originalReservation);
    var cancelKey = id();
    var cancelCommand = Map.of("expectedEmbryoVersion", 1, "reason", "Recipient unavailable");
    assertStatus(
        api.post(
            production.tenant().id(),
            "/transfer-reservations/" + originalReservation + ":cancel",
            cancelKey,
            cancelCommand),
        200);
    assertStatus(
        api.post(
            production.tenant().id(),
            "/transfer-reservations/" + originalReservation + ":cancel",
            cancelKey,
            cancelCommand),
        200);
    var replacement = id();
    assertStatus(
        api.post(
            production.tenant().id(),
            "/transfers:bulk-reserve",
            reservationBatch(
                id(), reservationItem(replacement, reference.embryo(), reference.cycle(), 2))),
        200);
    var performBatch = id();
    var race =
        concurrent(
            () ->
                api.post(
                    production.tenant().id(),
                    "/transfers:bulk-perform",
                    performBatch,
                    performanceBatch(
                        performBatch,
                        id(),
                        replacement,
                        3,
                        production.professional(),
                        Instant.parse("2026-09-01T12:00:00Z"))),
            () ->
                api.post(
                    production.tenant().id(),
                    "/transfer-reservations/" + replacement + ":cancel",
                    id(),
                    Map.of("expectedEmbryoVersion", 3, "reason", "Concurrent cancellation")));
    assertThat(race).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbc.queryForMap(
                "SELECT r.status,e.availability_status FROM embryo_transfer_reservation r JOIN embryo e ON e.organization_id=r.organization_id AND e.id=r.embryo_id WHERE r.id=?",
                replacement))
        .satisfiesAnyOf(
            state -> {
              assertThat(state.get("status")).isEqualTo("CONSUMED");
              assertThat(state.get("availability_status")).isEqualTo("TRANSFERRED");
            },
            state -> {
              assertThat(state.get("status")).isEqualTo("CANCELLED");
              assertThat(state.get("availability_status")).isEqualTo("AVAILABLE");
            });
  }

  private ReservationReference reservationRef(UUID reservation) {
    return jdbc.queryForObject(
        "SELECT embryo_id,recipient_cycle_id FROM embryo_transfer_reservation WHERE id=?",
        (result, row) ->
            new ReservationReference(
                result.getObject("embryo_id", UUID.class),
                result.getObject("recipient_cycle_id", UUID.class)),
        reservation);
  }

  private List<HttpResponse<String>> concurrent(
      Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
      throws Exception {
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var firstResponse =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return first.call();
              });
      var secondResponse =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(
          firstResponse.get(30, TimeUnit.SECONDS), secondResponse.get(30, TimeUnit.SECONDS));
    }
  }

  private record ReservationReference(UUID embryo, UUID cycle) {}
}
