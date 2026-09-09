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
