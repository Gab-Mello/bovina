package com.bovina;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.StableIds;
import com.bovina.support.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterDataIT {
  private static final TrustedTokens TOKENS = new TrustedTokens();
  private static final StableIds IDS = new StableIds();
  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
    registry.add("bovina.security.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.security.jwk-set-uri", () -> TOKENS.jwks().toString());
    registry.add("bovina.bootstrap.enabled", () -> true);
    registry.add("bovina.bootstrap.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.bootstrap.subject", () -> "master-data-bootstrap");
  }

  @AfterAll
  static void closeKeys() {
    TOKENS.close();
  }

  @Test
  void productRolesShareOneIdentityAndPreserveExistingClientContract() throws Exception {
    var tenant = tenant();
    var other = tenant();
    var id = IDS.next();
    var input =
        Map.of(
            "id",
            id,
            "type",
            "PERSON",
            "displayName",
            "Shared identity",
            "occurredAt",
            Instant.EPOCH);
    assertThat(post(tenant, "/clients", input).statusCode()).isEqualTo(201);
    var reuse = new HashMap<String, Object>(input);
    reuse.put("expectedVersion", 0);
    for (var path : List.of("/owners?scope=ANIMAL", "/suppliers", "/shipment-recipients")) {
      var key = IDS.next();
      var registered = post(tenant, path, key, reuse);
      assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
      assertThat(json.readTree(post(tenant, path, key, reuse).body()))
          .isEqualTo(json.readTree(registered.body()));
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM party WHERE id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party_role WHERE party_id=?", Integer.class, id))
        .isEqualTo(4);
    assertThat(get(tenant, "/clients/" + id).body())
        .doesNotContain("Party", "roles", "organizationId");
    assertThat(get(other, "/suppliers/" + id).statusCode()).isEqualTo(404);
    assertThat(get(tenant, "/clients?q=Shared&size=1").body()).contains("Shared identity");
    assertThat(get(tenant, "/parties/" + id).statusCode()).isEqualTo(404);
    var archived = post(tenant, "/clients/" + id + ":archive", Map.of("expectedVersion", 0));
    assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
    assertThat(post(tenant, "/owners?scope=MATERIAL", reuse).statusCode()).isEqualTo(409);
    assertThat(get(tenant, "/clients/" + id).statusCode()).isEqualTo(200);
  }

  @Test
  void propertiesRejectForeignOwnersAndStaleArchives() throws Exception {
    var a = tenant();
    var b = tenant();
    var owner = owner(a);
    var input = property(IDS.next(), owner);
    assertThat(post(b, "/farm-properties", input).statusCode()).isEqualTo(404);
    var response = post(a, "/farm-properties", input);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var id = input.get("id");
    assertThat(get(b, "/farm-properties/" + id).statusCode()).isEqualTo(404);
    assertThat(
            post(a, "/farm-properties/" + id + ":archive", Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post(a, "/farm-properties/" + id + ":archive", Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(409);
    assertThat(get(a, "/farm-properties?q=Farm").body()).contains("ARCHIVED");
  }

  @Test
  void locationsRemainDistinctTenantScopedAndHistoricallyReadable() throws Exception {
    var a = tenant();
    var b = tenant();
    var establishment = establishment(a);
    var second = establishment(a);
    var id = IDS.next();
    var input = Map.of("id", id, "name", "Main Lab", "type", "LAB");
    var path = "/establishments/" + establishment + "/operational-locations";
    assertThat(post(b, path, input).statusCode()).isEqualTo(404);
    var result = post(a, path, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    assertThat(
            post(a, path, Map.of("id", IDS.next(), "name", "main lab", "type", "STORAGE"))
                .statusCode())
        .isEqualTo(409);
    assertThat(get(a, "/establishments/" + second + "/operational-locations/" + id).statusCode())
        .isEqualTo(404);
    assertThat(get(b, path + "/" + id).statusCode()).isEqualTo(404);
    assertThat(post(a, path + "/" + id + ":deactivate", Map.of("expectedVersion", 0)).statusCode())
        .isEqualTo(200);
    assertThat(get(a, path).body()).contains("INACTIVE");
    assertThat(
            post(
                    a,
                    "/establishments/" + establishment + ":deactivate",
                    Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post(a, path, Map.of("id", IDS.next(), "name", "Other Lab", "type", "LAB"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void technicianAssignmentsKeepCredentialSnapshotsAndAllowDistinctProfessionals()
      throws Exception {
    var a = tenant();
    var b = tenant();
    var establishment = establishment(a);
    var professional = professional(a);
    var doc = IDS.next();
    var source = Map.of("id", doc, "type", "ART", "reference", "Declared source", "revision", "1");
    assertThat(post(a, "/document-references", source).statusCode()).isEqualTo(201);
    assertThat(get(b, "/document-references/" + doc).statusCode()).isEqualTo(404);
    var credential = credential(a, professional, doc);
    var id = IDS.next();
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
    var result = post(a, path, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    assertThat(result.body()).contains("CRMV declared", "123");
    var duplicate = new HashMap<String, Object>(input);
    duplicate.put("id", IDS.next());
    assertThat(post(a, path, duplicate).statusCode()).isEqualTo(409);
    var foreign = new HashMap<String, Object>(input);
    foreign.put("id", IDS.next());
    foreign.put("professionalId", professional(b));
    assertThat(post(a, path, foreign).statusCode()).isEqualTo(404);
    var second = professional(a);
    var secondCredential = credential(a, second, doc);
    var concurrent = new HashMap<String, Object>(input);
    concurrent.put("id", IDS.next());
    concurrent.put("professionalId", second);
    concurrent.put("credentialId", secondCredential);
    assertThat(post(a, path, concurrent).statusCode()).isEqualTo(201);
    assertThat(
            post(a, path + "/" + id + ":end", Map.of("expectedVersion", 0, "until", "2026-02-01"))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post(a, path + "/" + id + ":end", Map.of("expectedVersion", 1, "until", "2026-03-01"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            post(a, "/professionals/" + professional + ":deactivate", Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(get(a, path).body()).contains("CRMV declared", "2026-02-01");
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

  private UUID establishment(UUID tenant) throws Exception {
    var id = IDS.next();
    var response =
        post(
            tenant,
            "/establishments",
            Map.of(
                "id",
                id,
                "legalDisplayName",
                "Lab establishment",
                "operatingMode",
                "COMMERCIAL",
                "address",
                address()));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return id;
  }

  private UUID professional(UUID tenant) throws Exception {
    var id = IDS.next();
    var response =
        post(
            tenant,
            "/professionals",
            Map.of("id", id, "name", "Professional", "professionalType", "VETERINARIAN"));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return id;
  }

  private UUID credential(UUID tenant, UUID professional, UUID document) throws Exception {
    var id = IDS.next();
    var response =
        post(
            tenant,
            "/professionals/" + professional + "/credentials",
            Map.of(
                "id",
                id,
                "issuer",
                "CRMV declared",
                "jurisdiction",
                "SP",
                "number",
                "123",
                "period",
                Map.of("from", "2026-01-01"),
                "documentId",
                document));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return id;
  }

  private UUID tenant() throws Exception {
    var id = IDS.next();
    var result =
        request(
            "POST",
            "/bootstrap/organizations",
            null,
            IDS.next(),
            Map.of(
                "id",
                id,
                "legalName",
                "Master data tenant",
                "taxId",
                "test-" + id.toString().substring(0, 8),
                "timezone",
                "America/Sao_Paulo"));
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    return id;
  }

  private UUID owner(UUID tenant) throws Exception {
    var id = IDS.next();
    var result =
        post(
            tenant,
            "/owners?scope=ANIMAL",
            Map.of(
                "id", id, "type", "PERSON", "displayName", "Owner", "occurredAt", Instant.EPOCH));
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    return id;
  }

  private Map<String, Object> address() {
    return Map.of("addressLine", "Road 1", "municipality", "City", "state", "SP", "country", "BR");
  }

  private Map<String, Object> property(UUID id, UUID owner) {
    return Map.of("id", id, "name", "Farm", "ownerId", owner, "address", address());
  }

  private HttpResponse<String> post(UUID tenant, String path, Object body) throws Exception {
    return post(tenant, path, IDS.next(), body);
  }

  private HttpResponse<String> post(UUID tenant, String path, UUID key, Object body)
      throws Exception {
    return request("POST", path, tenant, key, body);
  }

  private HttpResponse<String> get(UUID tenant, String path) throws Exception {
    return request("GET", path, tenant, null, null);
  }

  private HttpResponse<String> request(
      String method, String path, UUID tenant, UUID key, Object body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(20))
            .header(
                "Authorization",
                "Bearer "
                    + TOKENS.token(
                        "master-data-bootstrap",
                        TOKENS.issuer().toString(),
                        "bovina-test",
                        Instant.now().plusSeconds(600)))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (tenant != null) builder.header("X-Organization-ID", tenant.toString());
    if (key != null) builder.header("Idempotency-Key", key.toString());
    if (body != null) builder.header("Content-Type", "application/json");
    try (var client = HttpClient.newHttpClient()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
