package com.bovina.transfer;

import static com.bovina.support.fixture.TransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FreshTransferIT extends AuthenticatedIntegrationTest {
  @Test
  void performedFreshTransferIsIdempotentUniqueAndTraceableThroughCanonicalLineage()
      throws Exception {
    var production = ProductionFixtures.freshEmbryos(api, tenant("Fresh Transfer Lab"), 1);
    var recipient =
        ProductionFixtures.animal(api, production.tenant().id(), "FEMALE", "Recipient A");
    var earlierCycle =
        openCycle(api, production.tenant().id(), recipient, LocalDate.of(2026, 8, 1));
    var transferCycle =
        openCycle(api, production.tenant().id(), recipient, LocalDate.of(2026, 9, 1));
    assertThat(
            api.get(production.tenant().id(), "/recipient-cycles?recipientId=" + recipient).body())
        .contains(earlierCycle.toString(), transferCycle.toString());

    var reservation = id();
    var reserveBatch = id();
    var reserveCommand =
        reservationBatch(
            reserveBatch,
            reservationItem(reservation, production.embryos().getFirst(), transferCycle, 0));
    var reserved =
        api.post(production.tenant().id(), "/transfers:bulk-reserve", reserveBatch, reserveCommand);
    assertStatus(reserved, 200);
    assertThat(
            json.readTree(
                api.post(
                        production.tenant().id(),
                        "/transfers:bulk-reserve",
                        reserveBatch,
                        reserveCommand)
                    .body()))
        .isEqualTo(json.readTree(reserved.body()));
    assertStatus(
        api.post(
            production.tenant().id(),
            "/transfers:bulk-reserve",
            reserveBatch,
            reservationBatch(
                reserveBatch,
                reservationItem(id(), production.embryos().getFirst(), transferCycle, 0))),
        409);

    var transfer = id();
    var performBatch = id();
    var performCommand =
        performanceBatch(
            performBatch,
            transfer,
            reservation,
            1,
            production.professional(),
            Instant.parse("2026-09-01T12:00:00Z"));
    assertStatus(
        api.post(production.tenant().id(), "/transfers:bulk-perform", performBatch, performCommand),
        200);
    assertStatus(
        api.post(production.tenant().id(), "/transfers:bulk-perform", performBatch, performCommand),
        200);

    var lineage = api.get(production.tenant().id(), "/transfers/" + transfer);
    assertStatus(lineage, 200);
    assertThat(lineage.body())
        .contains(
            production.donor().toString(),
            production.sire().toString(),
            production.semenBatch().toString(),
            production.collection().toString(),
            production.mating().toString());
    assertStatus(
        api.post(
            production.tenant().id(),
            "/transfers:bulk-reserve",
            reservationBatch(
                id(), reservationItem(id(), production.embryos().getFirst(), earlierCycle, 2))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer WHERE embryo_id=?",
                Integer.class,
                production.embryos().getFirst()))
        .isEqualTo(1);
  }
}
