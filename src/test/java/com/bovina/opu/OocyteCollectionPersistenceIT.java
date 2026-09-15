package com.bovina.opu;

import static com.bovina.support.fixture.OpuFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;

class OocyteCollectionPersistenceIT extends OpuIntegrationTest {
  @Test
  void checksTenantForeignKeysAndRuntimeGrantsProtectCollectionFacts() throws Exception {
    var opu = startedSession(api, tenant("Collection Integrity Lab"));
    var foreign = startedSession(api, tenant("Foreign Collection Lab"));
    var collection = id();
    var donor = donor(api, opu.tenant(), "Donor A");
    assertStatus(
        api.post(
            opu.tenant().id(),
            opu.path() + "/collections:bulk",
            collectionBatch(collection(collection, donor, 5, 3))),
        200);

    assertStatus(api.get(foreign.tenant().id(), "/oocyte-collections/" + collection), 404);
    assertStatus(api.get(foreign.tenant().id(), opu.path()), 404);
    var crossTenant =
        collectionBatch(collection(id(), donor(api, foreign.tenant(), "Foreign Donor"), 2, 1));
    var preview = api.post(opu.tenant().id(), opu.path() + "/collections:dry-run", crossTenant);
    assertStatus(preview, 200);
    assertThat(preview.body()).contains("DONOR_NOT_FOUND");

    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var invalidUpdate :
          List.of("viable=6", "total_recovered=-1", "viable=-1", "follicles_aspirated=-1")) {
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "UPDATE oocyte_collection SET "
                            + invalidUpdate
                            + " WHERE id='"
                            + collection
                            + "'"))
            .isInstanceOf(java.sql.SQLException.class)
            .extracting("SQLState")
            .isEqualTo("23514");
      }
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE oocyte_collection SET opu_session_id='"
                          + foreign.session()
                          + "' WHERE id='"
                          + collection
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23503");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "DELETE FROM oocyte_collection WHERE id='" + collection + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
    assertStatus(
        api.post(
            opu.tenant().id(),
            opu.path() + "/collections:bulk",
            collectionBatch(collection(id(), donor, 2, 1))),
        422);
  }
}
