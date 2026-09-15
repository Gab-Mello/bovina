package com.bovina.transfer;

import static com.bovina.support.fixture.TransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.fixture.ProductionFixtures.EmbryoProduction;
import com.bovina.support.fixture.TransferFixtures.PerformedTransfer;
import com.bovina.support.fixture.TransferFixtures.Reservation;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class TransferPersistenceIntegrityIT extends AuthenticatedIntegrationTest {
  private static final Instant TRANSFERRED_AT = Instant.parse("2026-09-01T12:00:00Z");

  @Test
  void thawedOriginCannotBeClaimedForFreshEmbryoWithoutAThawFact() throws Exception {
    var scenario = freshReservation();
    var batch =
        performanceBatch(
            id(),
            id(),
            scenario.reservation().id(),
            1,
            scenario.production().professional(),
            TRANSFERRED_AT);
    @SuppressWarnings("unchecked")
    var item = new HashMap<>((Map<String, Object>) ((List<?>) batch.get("items")).getFirst());
    item.put("origin", "THAWED");
    var command = new HashMap<>(batch);
    command.put("items", List.of(item));

    assertStatus(
        api.post(scenario.production().tenant().id(), "/transfers:bulk-perform", command), 400);
  }

  @Test
  void crossTenantReadsAndRecipientCycleForeignKeysRejectForeignAnimals() throws Exception {
    var scenario = freshReservation();
    var performed = performedTransfer(scenario);
    var foreign = tenant("Foreign Transfer Lab");

    assertStatus(api.get(foreign.id(), "/recipient-cycles/" + scenario.cycle()), 404);
    assertStatus(api.get(foreign.id(), "/transfers/" + performed.id()), 404);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO recipient_cycle(id,organization_id,recipient_animal_id,opened_on,status,origin_type,recorded_by,recorded_at) VALUES (?,?,?,'2026-09-01','OPEN','MANUAL',?,now())",
                    id(),
                    foreign.id(),
                    scenario.recipient(),
                    foreign.actorId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void uniquePerformedFactAndRuntimeGrantsPreserveTransferAndCheckHistory() throws Exception {
    var scenario = freshReservation();
    var performed = performedTransfer(scenario);
    var check = id();
    assertStatus(
        api.post(
            scenario.production().tenant().id(),
            "/pregnancy-checks:bulk",
            checkBatch(
                id(),
                checkItem(
                    check,
                    performed.id(),
                    Instant.parse("2026-10-01T12:00:00Z"),
                    "PREGNANT",
                    scenario.production().professional(),
                    null,
                    null))),
        200);

    var duplicateReservation = id();
    jdbc.update(
        "INSERT INTO embryo_transfer_reservation(id,organization_id,embryo_id,recipient_cycle_id,status,reserved_by,reserved_at,ended_by,ended_at,end_reason) VALUES (?,?,?,?,'CONSUMED',?,now(),?,now(),'TRANSFER_PERFORMED')",
        duplicateReservation,
        scenario.production().tenant().id(),
        scenario.production().embryos().getFirst(),
        scenario.cycle(),
        scenario.production().tenant().actorId(),
        scenario.production().tenant().actorId());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO embryo_transfer(id,organization_id,reservation_id,embryo_id,recipient_cycle_id,performed_at,performed_timezone,transfer_origin,operator_professional_id,origin_type,recorded_by,recorded_at) VALUES (?,?,?,?,?,now(),'UTC','FRESH',?,'MANUAL',?,now())",
                    id(),
                    scenario.production().tenant().id(),
                    duplicateReservation,
                    scenario.production().embryos().getFirst(),
                    scenario.cycle(),
                    scenario.production().professional(),
                    scenario.production().tenant().actorId()))
        .isInstanceOf(DataIntegrityViolationException.class);

    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE embryo_transfer SET notes='rewrite' WHERE id='"
                          + performed.id()
                          + "'"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE pregnancy_check SET observations='rewrite' WHERE id='" + check + "'"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  @Test
  void readOnlyMemberCanReadCycleButCannotCreateAnother() throws Exception {
    var tenant = tenant("Recipient Lab");
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient A");
    var cycle = openCycle(api, tenant.id(), recipient, LocalDate.of(2026, 9, 1));
    var subject = "read-only-recipient-" + id();
    assertStatus(
        api.post(
            tenant.id(),
            "/memberships",
            Map.of("id", id(), "subject", subject, "role", "READ_ONLY")),
        201);

    assertStatus(api.getAs(subject, tenant.id(), "/recipient-cycles/" + cycle), 200);
    assertStatus(
        api.postAs(
            subject,
            tenant.id(),
            "/recipient-cycles",
            id(),
            Map.of("id", id(), "recipientAnimalId", recipient, "openedOn", "2026-12-01")),
        403);
  }

  @Test
  void recipientCycleCloseRejectsAStaleExpectedVersion() throws Exception {
    var tenant = tenant("Cycle Follow-up Lab");
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient A");
    var cycle = openCycle(api, tenant.id(), recipient, LocalDate.of(2026, 9, 1));

    assertStatus(
        api.post(
            tenant.id(),
            "/recipient-cycles/" + cycle + ":close",
            Map.of(
                "expectedVersion",
                0,
                "closedOn",
                "2026-11-01",
                "reason",
                "Outcome follow-up complete")),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            "/recipient-cycles/" + cycle + ":close",
            Map.of("expectedVersion", 0, "closedOn", "2026-11-01", "reason", "Repeated close")),
        409);
  }

  private FreshReservation freshReservation() throws Exception {
    var production = ProductionFixtures.freshEmbryos(api, tenant("Historical Transfer Lab"), 1);
    var recipient =
        ProductionFixtures.animal(api, production.tenant().id(), "FEMALE", "Recipient A");
    var cycle = openCycle(api, production.tenant().id(), recipient, LocalDate.of(2026, 9, 1));
    var reservation =
        reservation(api, production.tenant().id(), production.embryos().getFirst(), cycle, 0);
    return new FreshReservation(production, recipient, cycle, reservation);
  }

  private PerformedTransfer performedTransfer(FreshReservation scenario) throws Exception {
    return perform(
        api,
        scenario.production().tenant().id(),
        scenario.reservation().id(),
        1,
        scenario.production().professional(),
        TRANSFERRED_AT);
  }

  private record FreshReservation(
      EmbryoProduction production, UUID recipient, UUID cycle, Reservation reservation) {}
}
