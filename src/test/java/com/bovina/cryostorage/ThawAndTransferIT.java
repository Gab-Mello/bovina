package com.bovina.cryostorage;

import static com.bovina.support.fixture.CryostorageFixtures.physicalMovement;
import static com.bovina.support.fixture.CryostorageFixtures.storageLocation;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.fixture.TransferFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThawAndTransferIT extends AuthenticatedIntegrationTest {
  @Test
  void thawedTransferRequiresWithdrawnItemLevelEvidenceAndPreservesLineage() throws Exception {
    var tenant = tenant("Thawed Transfer Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, tenant, production.embryos().getFirst(), production, ids);
    var packaged = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, ids);
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recipient A");
    var cycle = TransferFixtures.openCycle(api, tenant.id(), recipient, LocalDate.of(2026, 8, 30));
    var freshBatch = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/transfers:bulk-reserve",
            freshBatch,
            TransferFixtures.reservationBatch(
                freshBatch, TransferFixtures.reservationItem(id(), cryo.embryoId(), cycle, 0))),
        409);

    var rack =
        storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), "THAW_RACK", ids);
    var receive = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            receive,
            physicalMovement(
                receive,
                packaged.packageId(),
                "RECEIVE",
                null,
                rack,
                2,
                Instant.parse("2026-09-04T09:00:00Z"),
                null)),
        201);
    var withdraw = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/inventory-movements",
            withdraw,
            physicalMovement(
                withdraw,
                packaged.packageId(),
                "WITHDRAW",
                rack,
                null,
                3,
                Instant.parse("2026-09-04T09:00:00Z"),
                null)),
        201);
    var thawId = id();
    var thawCommand =
        Map.<String, Object>of(
            "id",
            thawId,
            "packageId",
            packaged.packageId(),
            "occurredAt",
            Instant.parse("2026-09-04T10:00:00Z"),
            "professionalId",
            production.professional(),
            "resultCode",
            "OBSERVED_VIABLE",
            "packageItemIds",
            List.of(packaged.packageItem()));
    assertStatus(api.post(tenant.id(), "/thaw-events", thawId, thawCommand), 201);
    assertStatus(api.post(tenant.id(), "/thaw-events", thawId, thawCommand), 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM thaw_event_item WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
    assertThat(api.get(tenant.id(), "/embryos/" + cryo.embryoId()).body())
        .contains("\"preservation\":\"THAWED\"");

    var transferId = id();
    var transfer =
        Map.<String, Object>of(
            "transferId",
            transferId,
            "reservationId",
            id(),
            "embryoId",
            cryo.embryoId(),
            "recipientCycleId",
            cycle,
            "thawEventId",
            thawId,
            "expectedEmbryoVersion",
            0,
            "performedAt",
            Instant.parse("2026-09-04T12:00:00Z"),
            "timezone",
            "America/Sao_Paulo",
            "operatorProfessionalId",
            production.professional());
    assertStatus(api.post(tenant.id(), "/thawed-transfers:perform", transferId, transfer), 201);
    assertStatus(api.post(tenant.id(), "/thawed-transfers:perform", transferId, transfer), 201);
    var details = api.get(tenant.id(), "/transfers/" + transferId);
    assertStatus(details, 200);
    assertThat(details.body())
        .contains(
            "\"origin\":\"THAWED\"",
            thawId.toString(),
            production.donor().toString(),
            production.sire().toString(),
            production.semenBatch().toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
  }
}
