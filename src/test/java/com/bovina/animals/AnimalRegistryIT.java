package com.bovina.animals;

import static com.bovina.support.fixture.MasterDataFixtures.*;
import static com.bovina.support.integration.PostgresTestAssertions.awaitLockWait;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.fixture.TransferFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnimalRegistryIT extends AuthenticatedIntegrationTest {
  @Test
  void searchDerivesSireAndRecipientRolesFromTenantScopedFacts() throws Exception {
    var tenant = tenant("Contextual Animal Roles Lab");
    var foreign = tenant("Foreign Contextual Roles Lab");
    var production = ProductionFixtures.fertilizationInputs(api, tenant, 1);
    var foreignProduction = ProductionFixtures.fertilizationInputs(api, foreign, 1);
    var unusedMale = ProductionFixtures.animal(api, tenant.id(), "MALE", "Unused Male");
    var unusedFemale = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Unused Female");
    var owner = owner(api, tenant.id(), "Sire Owner");
    assertStatus(
        api.post(
            tenant.id(),
            "/animals/" + production.sire() + "/ownership",
            Map.of("id", id(), "ownerId", owner, "period", Map.of("from", "2026-01-01"))),
        201);
    TransferFixtures.openCycle(api, tenant.id(), production.donor(), LocalDate.of(2026, 9, 1));
    TransferFixtures.openCycle(
        api, foreign.id(), foreignProduction.donor(), LocalDate.of(2026, 9, 1));

    var sires = api.get(tenant.id(), "/animals?role=SIRE&size=1&q=Sire");
    assertStatus(sires, 200);
    assertThat(sires.body())
        .contains(production.sire().toString())
        .doesNotContain(unusedMale.toString(), foreignProduction.sire().toString());
    var ownedSires =
        api.get(tenant.id(), "/animals?role=SIRE&ownerId=" + owner + "&ownedOn=2026-09-01&size=1");
    assertStatus(ownedSires, 200);
    assertThat(ownedSires.body()).contains(production.sire().toString());
    assertStatus(
        api.get(tenant.id(), "/animals?role=SIRE&ownerId=" + owner + "&ownedOn=2025-09-01"), 200);
    assertThat(
            api.get(tenant.id(), "/animals?role=SIRE&ownerId=" + owner + "&ownedOn=2025-09-01")
                .body())
        .doesNotContain(production.sire().toString());

    var recipients = api.get(tenant.id(), "/animals?role=RECIPIENT&size=1");
    assertStatus(recipients, 200);
    assertThat(recipients.body())
        .contains(production.donor().toString())
        .doesNotContain(unusedFemale.toString(), foreignProduction.donor().toString());
    assertThat(api.get(tenant.id(), "/animals?role=DONOR&size=1").body())
        .contains(production.donor().toString());
  }

  @Test
  void activeIdentifiersAreNormalizedAndUniqueWithinTheirIssuerAndTenant() throws Exception {
    var tenant = tenant("Animal Registry Lab");
    var foreign = tenant("Foreign Animal Registry Lab");
    var firstAnimal = animal(api, tenant.id(), "Animal A");
    var secondAnimal = animal(api, tenant.id(), "Animal B");
    var foreignAnimal = animal(api, foreign.id(), "Foreign Animal");
    var identifier = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/animals/" + firstAnimal + "/identifiers",
            Map.of("id", identifier, "type", "EAR_TAG", "value", " 00-ab/9 ")),
        201);
    var duplicate = Map.of("id", id(), "type", "EAR_TAG", "value", "00-AB/9");
    assertStatus(
        api.post(tenant.id(), "/animals/" + secondAnimal + "/identifiers", duplicate), 409);
    assertStatus(
        api.post(
            foreign.id(),
            "/animals/" + foreignAnimal + "/identifiers",
            Map.of("id", id(), "type", "EAR_TAG", "value", "00-AB/9")),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            "/animals/" + foreignAnimal + "/identifiers",
            Map.of("id", id(), "type", "EID", "value", "123")),
        404);
    assertStatus(
        api.post(
            tenant.id(),
            "/animals/" + secondAnimal + "/identifiers",
            Map.of(
                "id", id(),
                "type", "EAR_TAG",
                "value", "00-AB/9",
                "issuer", "Other registry")),
        201);

    assertStatus(
        api.post(
            tenant.id(),
            "/animals/" + firstAnimal + "/identifiers/" + identifier + ":retire",
            Map.of(
                "expectedVersion", 0,
                "disposition", "CORRECTED",
                "reason", "Wrong tag source")),
        200);
    assertStatus(
        api.post(tenant.id(), "/animals/" + secondAnimal + "/identifiers", duplicate), 201);
    assertThat(api.get(tenant.id(), "/animals/" + firstAnimal + "/identifiers").body())
        .contains("CORRECTED", "00-ab/9");
    assertStatus(api.get(tenant.id(), "/animals?q=00-ab%2F9"), 200);
    assertStatus(api.get(foreign.id(), "/animals/" + firstAnimal), 404);
    assertStatus(
        api.post(tenant.id(), "/animals/" + firstAnimal + ":archive", Map.of("expectedVersion", 0)),
        200);
    assertStatus(
        api.post(tenant.id(), "/animals/" + firstAnimal + ":archive", Map.of("expectedVersion", 0)),
        409);
    assertStatus(
        api.post(
            tenant.id(),
            "/animals/" + firstAnimal + "/identifiers",
            Map.of("id", id(), "type", "OTHER", "value", "new")),
        409);
  }

  @Test
  void ownershipPreservesCoownersAndRejectsOnlyOverlappingDuplicateAssignments() throws Exception {
    var tenant = tenant("Animal Ownership Lab");
    var foreign = tenant("Foreign Ownership Lab");
    var animal = animal(api, tenant.id(), "Recipient A");
    var firstOwner = owner(api, tenant.id(), "Owner A");
    var secondOwner = owner(api, tenant.id(), "Owner B");
    var assignment = id();
    var path = "/animals/" + animal + "/ownership";
    assertStatus(
        api.post(
            tenant.id(),
            path,
            Map.of(
                "id", assignment,
                "ownerId", firstOwner,
                "period", Map.of("from", "2026-01-01"))),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            path,
            Map.of(
                "id", id(),
                "ownerId", firstOwner,
                "period", Map.of("from", "2026-01-02"))),
        409);
    assertStatus(
        api.post(
            tenant.id(),
            path,
            Map.of(
                "id", id(),
                "ownerId", secondOwner,
                "period", Map.of("from", "2026-01-01"))),
        201);
    assertStatus(
        api.post(
            tenant.id(),
            path,
            Map.of(
                "id", id(),
                "ownerId", owner(api, foreign.id(), "Foreign Owner"),
                "period", Map.of("from", "2026-01-01"))),
        404);
    assertStatus(
        api.post(
            tenant.id(),
            path + "/" + assignment + ":end",
            Map.of("expectedVersion", 0, "until", "2026-02-01")),
        200);
    assertStatus(
        api.post(
            tenant.id(),
            path,
            Map.of(
                "id", id(),
                "ownerId", firstOwner,
                "period", Map.of("from", "2026-02-01"))),
        201);
    assertThat(api.get(tenant.id(), path).body()).contains("2026-02-01");
    assertThat(
            api.get(tenant.id(), "/animals?ownerId=" + firstOwner + "&ownedOn=2026-01-15").body())
        .contains(animal.toString());
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE animal_ownership_assignment SET owner_id='"
                          + secondOwner
                          + "' WHERE id='"
                          + assignment
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      assertThatThrownBy(
              () -> statement.executeUpdate("DELETE FROM animal WHERE id='" + animal + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  @Test
  void concurrentDuplicateOwnershipAssignmentsSerializeOnTheAnimal() throws Exception {
    var tenant = tenant("Concurrent Ownership Lab");
    var animal = animal(api, tenant.id(), "Recipient A");
    var owner = owner(api, tenant.id(), "Owner A");
    try (var connection = TestDatabase.runtimeConnection();
        var lock =
            connection.prepareStatement(
                "SELECT id FROM animal WHERE organization_id=? AND id=? FOR UPDATE");
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      lock.setObject(1, tenant.id());
      lock.setObject(2, animal);
      lock.executeQuery().close();
      var first =
          executor.submit(
              () ->
                  api.post(
                      tenant.id(),
                      "/animals/" + animal + "/ownership",
                      Map.of(
                          "id", id(),
                          "ownerId", owner,
                          "period", Map.of("from", "2026-01-01"))));
      var second =
          executor.submit(
              () ->
                  api.post(
                      tenant.id(),
                      "/animals/" + animal + "/ownership",
                      Map.of(
                          "id", id(),
                          "ownerId", owner,
                          "period", Map.of("from", "2026-01-01"))));
      try {
        awaitLockWait("animal", 2);
      } finally {
        connection.rollback();
      }
      assertThat(
              List.of(
                  first.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode(),
                  second.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM animal_ownership_assignment WHERE organization_id=? AND animal_id=?",
                Integer.class,
                tenant.id(),
                animal))
        .isEqualTo(1);
  }
}
