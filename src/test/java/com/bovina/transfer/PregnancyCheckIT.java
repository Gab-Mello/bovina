package com.bovina.transfer;

import static com.bovina.support.fixture.TransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PregnancyCheckIT extends AuthenticatedIntegrationTest {
  @Test
  void correctionsAndInvalidationsPreserveHistoryAndChangeTheDerivedLatestOutcome()
      throws Exception {
    var production = ProductionFixtures.freshEmbryos(api, tenant("Outcome Follow-up Lab"), 1);
    var recipient =
        ProductionFixtures.animal(api, production.tenant().id(), "FEMALE", "Recipient A");
    var cycle = openCycle(api, production.tenant().id(), recipient, LocalDate.of(2026, 9, 1));
    var reservation =
        reservation(api, production.tenant().id(), production.embryos().getFirst(), cycle, 0);
    var performed =
        perform(
            api,
            production.tenant().id(),
            reservation.id(),
            1,
            production.professional(),
            Instant.parse("2026-09-01T12:00:00Z"));

    var d30 = id();
    var d30Batch = id();
    var d30Command =
        checkBatch(
            d30Batch,
            checkItem(
                d30,
                performed.id(),
                Instant.parse("2026-10-01T12:00:00Z"),
                "PREGNANT",
                production.professional(),
                null,
                null));
    assertStatus(
        api.post(production.tenant().id(), "/pregnancy-checks:bulk", d30Batch, d30Command), 200);
    assertStatus(
        api.post(production.tenant().id(), "/pregnancy-checks:bulk", d30Batch, d30Command), 200);

    var superseded = id();
    assertStatus(
        api.post(
            production.tenant().id(),
            "/pregnancy-checks:bulk",
            checkBatch(
                id(),
                checkItem(
                    superseded,
                    performed.id(),
                    Instant.parse("2026-10-31T12:00:00Z"),
                    "PREGNANCY_LOSS",
                    production.professional(),
                    null,
                    null))),
        200);
    var corrected = id();
    assertStatus(
        api.post(
            production.tenant().id(),
            "/pregnancy-checks:bulk",
            checkBatch(
                id(),
                checkItem(
                    corrected,
                    performed.id(),
                    Instant.parse("2026-10-31T12:00:00Z"),
                    "NOT_PREGNANT",
                    production.professional(),
                    superseded,
                    "Corrected diagnostic interpretation"))),
        200);

    assertThat(
            api.get(production.tenant().id(), "/transfers/" + performed.id() + "/pregnancy-outcome")
                .body())
        .contains(corrected.toString(), "NOT_PREGNANT");
    var history =
        json.readTree(
            api.get(production.tenant().id(), "/transfers/" + performed.id() + "/pregnancy-checks")
                .body());
    assertThat(history.get("items").size()).isEqualTo(3);
    assertThat(history.toString())
        .contains(superseded.toString(), "Corrected diagnostic interpretation");
    assertThat(
            api.get(production.tenant().id(), "/pregnancy-follow-ups?cohort=D30&asOf=2026-10-01")
                .body())
        .contains(performed.id().toString(), d30.toString(), "RECORDED");
    assertThat(
            api.get(production.tenant().id(), "/pregnancy-follow-ups?cohort=D60&asOf=2026-10-31")
                .body())
        .contains(performed.id().toString(), corrected.toString(), "RECORDED");

    var invalidationKey = id();
    var invalidation = Map.of("reason", "Diagnostic evidence withdrawn");
    assertStatus(
        api.post(
            production.tenant().id(),
            "/pregnancy-checks/" + corrected + ":invalidate",
            invalidationKey,
            invalidation),
        200);
    assertStatus(
        api.post(
            production.tenant().id(),
            "/pregnancy-checks/" + corrected + ":invalidate",
            invalidationKey,
            invalidation),
        200);
    assertThat(
            api.get(production.tenant().id(), "/transfers/" + performed.id() + "/pregnancy-outcome")
                .body())
        .contains(d30.toString(), "PREGNANT")
        .doesNotContain(corrected.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pregnancy_check WHERE transfer_id=?",
                Integer.class,
                performed.id()))
        .isEqualTo(3);
  }
}
