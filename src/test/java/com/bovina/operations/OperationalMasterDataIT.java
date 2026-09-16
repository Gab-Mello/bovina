package com.bovina.operations;

import static com.bovina.support.fixture.MasterDataFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.bovina.identity.application.*;
import com.bovina.support.TestDatabase;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperationalMasterDataIT extends AuthenticatedIntegrationTest {
  @Autowired private TenantAccess access;

  private com.bovina.platform.application.ExecutionContext context(UUID tenant) {
    return access.resolve(
        new AuthenticatedIdentity(issuer(), "integration-bootstrap"), tenant, id());
  }

  @Test
  void propertiesRejectForeignOwnersAndStaleArchives() throws Exception {
    var a = tenant().id();
    var b = tenant().id();
    var owner = owner(api, a, "Owner A");
    var input = property(id(), owner);
    assertThat(api.post(b, "/farm-properties", input).statusCode()).isEqualTo(404);
    var response = api.post(a, "/farm-properties", input);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var id = input.get("id");
    assertThat(api.get(b, "/farm-properties/" + id).statusCode()).isEqualTo(404);
    assertThat(
            api.post(a, "/farm-properties/" + id + ":archive", Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            api.post(a, "/farm-properties/" + id + ":archive", Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(409);
    assertThat(api.get(a, "/farm-properties?q=Farm").body()).contains("ARCHIVED");
  }

  @Test
  void locationsRemainDistinctTenantScopedAndHistoricallyReadable() throws Exception {
    var a = tenant().id();
    var b = tenant().id();
    var establishment = establishment(api, a, "Lab Establishment");
    var second = establishment(api, a, "Lab Establishment");
    var id = id();
    var input = Map.of("id", id, "name", "Main Lab", "type", "LAB");
    var path = "/establishments/" + establishment + "/operational-locations";
    assertThat(api.post(b, path, input).statusCode()).isEqualTo(404);
    var result = api.post(a, path, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    assertThat(
            api.post(a, path, Map.of("id", id(), "name", "main lab", "type", "STORAGE"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            api.get(a, "/establishments/" + second + "/operational-locations/" + id).statusCode())
        .isEqualTo(404);
    assertThat(api.get(b, path + "/" + id).statusCode()).isEqualTo(404);
    assertThat(
            api.post(a, path + "/" + id + ":deactivate", Map.of("expectedVersion", 0)).statusCode())
        .isEqualTo(200);
    assertThat(api.get(a, path).body()).contains("INACTIVE");
    assertThat(
            api.post(
                    a,
                    "/establishments/" + establishment + ":deactivate",
                    Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            api.post(a, path, Map.of("id", id(), "name", "Other Lab", "type", "LAB")).statusCode())
        .isEqualTo(409);
  }

  @Test
  void technicianAssignmentsKeepCredentialSnapshotsAndAllowDistinctProfessionals()
      throws Exception {
    var a = tenant().id();
    var b = tenant().id();
    var establishment = establishment(api, a, "Lab Establishment");
    var professional = professional(api, a, "Veterinarian");
    var doc = id();
    var source = Map.of("id", doc, "type", "ART", "reference", "Declared source", "revision", "1");
    assertThat(api.post(a, "/document-references", source).statusCode()).isEqualTo(201);
    assertThat(api.get(b, "/document-references/" + doc).statusCode()).isEqualTo(404);
    var credential = credential(api, a, professional, doc);
    var id = id();
    var path = "/establishments/" + establishment + "/responsible-technicians";
    var input =
        Map.of(
            "id",
            id,
            "professionalId",
            professional,
            "credentialId",
            credential,
            "documentId",
            doc,
            "period",
            Map.of("from", "2026-01-01"));
    var result = api.post(a, path, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    assertThat(result.body()).contains("CRMV declared", "123");
    var duplicate = new HashMap<String, Object>(input);
    duplicate.put("id", id());
    assertThat(api.post(a, path, duplicate).statusCode()).isEqualTo(409);
    var foreign = new HashMap<String, Object>(input);
    foreign.put("id", id());
    foreign.put("professionalId", professional(api, b, "Veterinarian"));
    assertThat(api.post(a, path, foreign).statusCode()).isEqualTo(404);
    var second = professional(api, a, "Veterinarian");
    var secondCredential = credential(api, a, second, doc);
    var concurrent = new HashMap<String, Object>(input);
    concurrent.put("id", id());
    concurrent.put("professionalId", second);
    concurrent.put("credentialId", secondCredential);
    assertThat(api.post(a, path, concurrent).statusCode()).isEqualTo(201);
    assertThat(
            api.post(
                    a,
                    path + "/" + id + ":end",
                    Map.of("expectedVersion", 0, "until", "2026-02-01"))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            api.post(
                    a,
                    path + "/" + id + ":end",
                    Map.of("expectedVersion", 1, "until", "2026-03-01"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            api.post(
                    a,
                    "/professionals/" + professional + ":deactivate",
                    Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(api.get(a, path).body()).contains("CRMV declared", "2026-02-01");
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate("DELETE FROM document_reference WHERE id='" + doc + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE professional_credential SET number='changed' WHERE id='"
                          + credential
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  @Test
  void establishmentCapabilitiesAreExplicitAndDoNotCreateDefaultLocations() throws Exception {
    var tenant = tenant().id();
    var id = id();
    var input =
        Map.of(
            "id",
            id,
            "legalDisplayName",
            "Lab",
            "operatingMode",
            "COMMERCIAL",
            "address",
            address(),
            "capabilities",
            List.of("EMBRYO_PRODUCTION", "TRANSFER"),
            "registrationValidFrom",
            "2026-01-01",
            "registrationValidUntil",
            "2027-01-01");
    var key = id();
    var result = api.post(tenant, "/establishments", key, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    var reordered = new HashMap<String, Object>(input);
    reordered.put("capabilities", List.of("TRANSFER", "EMBRYO_PRODUCTION"));
    assertThat(json.readTree(api.post(tenant, "/establishments", key, reordered).body()))
        .isEqualTo(json.readTree(result.body()));
    assertThat(api.get(tenant, "/establishments?size=1").body())
        .contains("EMBRYO_PRODUCTION", "TRANSFER");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operational_location WHERE establishment_id=?",
                Integer.class,
                id))
        .isZero();
    animal(api, tenant, "Animal A");
    var observed = api.get(tenant, "/animals?role=DONOR");
    assertThat(observed.statusCode()).isEqualTo(200);
    assertThat(json.readTree(observed.body()).path("items").size()).isZero();
  }
}
