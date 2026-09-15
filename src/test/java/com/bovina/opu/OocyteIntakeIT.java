package com.bovina.opu;

import static com.bovina.support.fixture.OpuFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OocyteIntakeIT extends OpuIntegrationTest {
  @Test
  void transportRecordsObservedQuantitiesWithoutChangingCollectionCountsOrClaimingCompliance()
      throws Exception {
    var opu = startedSession(api, tenant("Oocyte Transport Lab"));
    var collection = id();
    assertStatus(
        api.post(
            opu.tenant().id(),
            opu.path() + "/collections:bulk",
            collectionBatch(collection(collection, donor(api, opu.tenant(), "Donor A"), 8, 6))),
        200);
    var transport = id();
    var command =
        Map.of(
            "id",
            transport,
            "sourceSessionId",
            opu.session(),
            "destinationEstablishmentId",
            opu.establishment(),
            "dispatchedAt",
            Instant.EPOCH,
            "receivedAt",
            Instant.EPOCH.plusSeconds(36 * 3600),
            "items",
            List.of(
                Map.of(
                    "collectionId", collection, "quantityAtDispatch", 8, "quantityAtReceipt", 5)));
    var key = id();

    var created = api.post(opu.tenant().id(), "/oocyte-transports", key, command);

    assertStatus(created, 201);
    assertThat(
            json.readTree(api.post(opu.tenant().id(), "/oocyte-transports", key, command).body()))
        .isEqualTo(json.readTree(created.body()));
    var read = api.get(opu.tenant().id(), "/oocyte-transports/" + transport);
    assertStatus(read, 200);
    assertThat(read.body())
        .contains("quantityAtDispatch", "quantityAtReceipt")
        .doesNotContain("WITHIN_PROTOCOL", "ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT viable FROM oocyte_collection WHERE id=?", Integer.class, collection))
        .isEqualTo(6);
    var foreign = startedSession(api, tenant("Foreign Transport Lab"));
    assertStatus(api.get(foreign.tenant().id(), "/oocyte-transports/" + transport), 404);
    var mismatch = new HashMap<String, Object>(command);
    mismatch.put("id", id());
    mismatch.put("sourceSessionId", foreign.session());
    assertStatus(api.post(foreign.tenant().id(), "/oocyte-transports", mismatch), 404);
    mismatch.put("destinationEstablishmentId", foreign.establishment());
    assertStatus(api.post(foreign.tenant().id(), "/oocyte-transports", mismatch), 409);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE oocyte_transport SET notes='rewrite' WHERE id='" + transport + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  @Test
  void externalReceiptDoesNotFabricateLocalSessionDonorOrApproval() throws Exception {
    var opu = startedSession(api, tenant("External Intake Lab"));
    var receipt = id();
    var key = id();
    var command =
        Map.of(
            "id",
            receipt,
            "receivedAt",
            Instant.EPOCH,
            "sourceReference",
            "External lab declared shipment 42",
            "totalReceived",
            12);

    var created = api.post(opu.tenant().id(), "/external-oocyte-receipts", key, command);

    assertStatus(created, 201);
    assertThat(created.body())
        .contains("RECEIVED", "MANUAL")
        .doesNotContain("ACCEPTED", "donorId", "sourceOpuSessionId");
    assertThat(
            json.readTree(
                api.post(opu.tenant().id(), "/external-oocyte-receipts", key, command).body()))
        .isEqualTo(json.readTree(created.body()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM opu_session WHERE organization_id=?",
                Integer.class,
                opu.tenant().id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE organization_id=?",
                Integer.class,
                opu.tenant().id()))
        .isZero();
    var invalid = new HashMap<String, Object>(command);
    invalid.remove("totalReceived");
    assertStatus(api.post(opu.tenant().id(), "/external-oocyte-receipts", invalid), 400);
    var foreign = startedSession(api, tenant("Foreign Intake Lab"));
    assertStatus(api.get(foreign.tenant().id(), "/external-oocyte-receipts/" + receipt), 404);
  }
}
