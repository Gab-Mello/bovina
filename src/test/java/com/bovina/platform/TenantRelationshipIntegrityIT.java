package com.bovina.platform;

import static com.bovina.support.fixture.MasterDataFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.bovina.identity.application.*;
import com.bovina.parties.application.ClientImports;
import com.bovina.protocols.application.Protocols;
import com.bovina.support.TestDatabase;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantRelationshipIntegrityIT extends AuthenticatedIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private Protocols protocols;
  @Autowired private ClientImports imports;

  private com.bovina.platform.application.ExecutionContext context(UUID tenant) {
    return access.resolve(
        new AuthenticatedIdentity(issuer(), "integration-bootstrap"), tenant, id());
  }

  @Test
  void compoundForeignKeysRejectCrossTenantMasterDataEvenWithoutApplicationChecks()
      throws Exception {
    var a = tenant().id();
    var b = tenant().id();
    var ownerA = owner(api, a, "Owner A");
    var ownerB = owner(api, b, "Owner A");
    var animalA = animal(api, a, "Animal A");
    var animalB = animal(api, b, "Animal A");
    var establishmentA = establishment(api, a, "Lab Establishment");
    var establishmentB = establishment(api, b, "Lab Establishment");
    var location = id();
    assertThat(
            api.post(
                    a,
                    "/establishments/" + establishmentA + "/operational-locations",
                    Map.of("id", location, "name", "Local", "type", "LAB"))
                .statusCode())
        .isEqualTo(201);
    var breed = id();
    assertThat(api.post(b, "/breeds", Map.of("id", breed, "name", "Declared breed")).statusCode())
        .isEqualTo(201);
    var actor = context(a).actorId();
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var sql :
          List.of(
              "UPDATE operational_location SET establishment_id='"
                  + establishmentB
                  + "' WHERE id='"
                  + location
                  + "'",
              "UPDATE animal SET breed_id='" + breed + "' WHERE id='" + animalA + "'",
              "INSERT INTO animal_ownership_assignment(id,organization_id,animal_id,owner_id,valid_from,recorded_by,recorded_at) VALUES ('"
                  + id()
                  + "','"
                  + a
                  + "','"
                  + animalA
                  + "','"
                  + ownerB
                  + "','2026-01-01','"
                  + actor
                  + "',now())",
              "INSERT INTO animal_ownership_assignment(id,organization_id,animal_id,owner_id,valid_from,recorded_by,recorded_at) VALUES ('"
                  + id()
                  + "','"
                  + a
                  + "','"
                  + animalB
                  + "','"
                  + ownerA
                  + "','2026-01-01','"
                  + actor
                  + "',now())"))
        assertThatThrownBy(() -> statement.executeUpdate(sql))
            .isInstanceOf(java.sql.SQLException.class)
            .extracting("SQLState")
            .isEqualTo("23503");
    }
  }
}
