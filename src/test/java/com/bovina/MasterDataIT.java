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
  @Autowired com.bovina.protocols.application.Protocols protocols;
  @Autowired com.bovina.identity.application.TenantAccess access;

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

  @Test
  void animalIdentifiersEnforceNormalizedActiveUniquenessWithNullIssuer() throws Exception {
    var a = tenant();
    var b = tenant();
    var first = animal(a);
    var second = animal(a);
    var foreign = animal(b);
    var identifier = IDS.next();
    var input = Map.of("id", identifier, "type", "EAR_TAG", "value", " 00-ab/9 ");
    assertThat(post(a, "/animals/" + first + "/identifiers", input).statusCode()).isEqualTo(201);
    var duplicate = Map.of("id", IDS.next(), "type", "EAR_TAG", "value", "00-AB/9");
    assertThat(post(a, "/animals/" + second + "/identifiers", duplicate).statusCode())
        .isEqualTo(409);
    assertThat(
            post(
                    b,
                    "/animals/" + foreign + "/identifiers",
                    Map.of("id", IDS.next(), "type", "EAR_TAG", "value", "00-AB/9"))
                .statusCode())
        .isEqualTo(201);
    assertThat(
            post(
                    a,
                    "/animals/" + foreign + "/identifiers",
                    Map.of("id", IDS.next(), "type", "EID", "value", "123"))
                .statusCode())
        .isEqualTo(404);
    assertThat(
            post(
                    a,
                    "/animals/" + second + "/identifiers",
                    Map.of(
                        "id",
                        IDS.next(),
                        "type",
                        "EAR_TAG",
                        "value",
                        "00-AB/9",
                        "issuer",
                        "Other registry"))
                .statusCode())
        .isEqualTo(201);
    var retired =
        post(
            a,
            "/animals/" + first + "/identifiers/" + identifier + ":retire",
            Map.of("expectedVersion", 0, "disposition", "CORRECTED", "reason", "Wrong tag source"));
    assertThat(retired.statusCode()).as(retired.body()).isEqualTo(200);
    assertThat(post(a, "/animals/" + second + "/identifiers", duplicate).statusCode())
        .isEqualTo(201);
    assertThat(get(a, "/animals/" + first + "/identifiers").body())
        .contains("CORRECTED", "00-ab/9");
    assertThat(get(a, "/animals?q=00-ab%2F9").statusCode()).isEqualTo(200);
    assertThat(get(b, "/animals/" + first).statusCode()).isEqualTo(404);
    var archived = post(a, "/animals/" + first + ":archive", Map.of("expectedVersion", 0));
    assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
    assertThat(post(a, "/animals/" + first + ":archive", Map.of("expectedVersion", 0)).statusCode())
        .isEqualTo(409);
    assertThat(
            post(
                    a,
                    "/animals/" + first + "/identifiers",
                    Map.of("id", IDS.next(), "type", "OTHER", "value", "new"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void ownershipKeepsCoownersAndRejectsOverlappingDuplicateAssignments() throws Exception {
    var a = tenant();
    var b = tenant();
    var animal = animal(a);
    var first = owner(a);
    var second = owner(a);
    var assignment = IDS.next();
    var path = "/animals/" + animal + "/ownership";
    var input = Map.of("id", assignment, "ownerId", first, "period", Map.of("from", "2026-01-01"));
    var response = post(a, path, input);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(
            post(
                    a,
                    path,
                    Map.of(
                        "id", IDS.next(), "ownerId", first, "period", Map.of("from", "2026-01-02")))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            post(
                    a,
                    path,
                    Map.of(
                        "id",
                        IDS.next(),
                        "ownerId",
                        second,
                        "period",
                        Map.of("from", "2026-01-01")))
                .statusCode())
        .isEqualTo(201);
    assertThat(
            post(
                    a,
                    path,
                    Map.of(
                        "id",
                        IDS.next(),
                        "ownerId",
                        owner(b),
                        "period",
                        Map.of("from", "2026-01-01")))
                .statusCode())
        .isEqualTo(404);
    assertThat(
            post(
                    a,
                    path + "/" + assignment + ":end",
                    Map.of("expectedVersion", 0, "until", "2026-02-01"))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post(
                    a,
                    path,
                    Map.of(
                        "id", IDS.next(), "ownerId", first, "period", Map.of("from", "2026-02-01")))
                .statusCode())
        .isEqualTo(201);
    assertThat(get(a, path).body()).contains("2026-02-01");
    assertThat(get(a, "/animals?ownerId=" + first + "&ownedOn=2026-01-15").body())
        .contains(animal.toString());
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE animal_ownership_assignment SET owner_id='"
                          + second
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
  void publishedProtocolsStayImmutableAndKeepTheirExactPurposeAndRevision() throws Exception {
    var a = tenant();
    var b = tenant();
    var definition = IDS.next();
    var id = IDS.next();
    var created =
        post(
            a,
            "/protocol-definitions",
            Map.of(
                "id",
                definition,
                "purpose",
                "OOCYTE_TRANSPORT",
                "name",
                "Declared transport protocol"));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var path = "/protocol-definitions/" + definition + "/versions";
    var input =
        Map.of(
            "id",
            id,
            "revision",
            "1.0",
            "effectivePeriod",
            Map.of("from", "2026-01-01", "until", "2027-01-01"),
            "contentReference",
            "lab-controlled-reference/1.0",
            "checksum",
            "a".repeat(64));
    assertThat(post(b, path, input).statusCode()).isEqualTo(404);
    var key = IDS.next();
    var published = post(a, path, key, input);
    assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
    assertThat(json.readTree(post(a, path, key, input).body()))
        .isEqualTo(json.readTree(published.body()));
    var duplicate = new HashMap<String, Object>(input);
    duplicate.put("id", IDS.next());
    assertThat(post(a, path, duplicate).statusCode()).isEqualTo(409);
    var next = new HashMap<String, Object>(input);
    next.put("id", IDS.next());
    next.put("revision", "2.0");
    next.put("checksum", "b".repeat(64));
    assertThat(post(a, path, next).statusCode()).isEqualTo(201);
    var context =
        access.resolve(
            new com.bovina.identity.application.AuthenticatedIdentity(
                TOKENS.issuer().toString(), "master-data-bootstrap"),
            a,
            IDS.next());
    assertThat(
            protocols
                .requireApplicable(context, id, "OOCYTE_TRANSPORT", LocalDate.of(2026, 6, 1))
                .revision())
        .isEqualTo("1.0");
    assertThatThrownBy(
            () ->
                protocols.requireApplicable(
                    context, id, "CRYOPRESERVATION", LocalDate.of(2026, 6, 1)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var sql :
          List.of(
              "UPDATE protocol_version SET checksum='" + "c".repeat(64) + "' WHERE id='" + id + "'",
              "DELETE FROM protocol_version WHERE id='" + id + "'"))
        assertThatThrownBy(() -> statement.executeUpdate(sql))
            .isInstanceOf(java.sql.SQLException.class)
            .extracting("SQLState")
            .isEqualTo("42501");
    }
    assertThat(
            post(
                    a,
                    "/protocol-definitions/" + definition + ":deactivate",
                    Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(json.readTree(get(a, path + "/" + id).body()))
        .isEqualTo(json.readTree(published.body()));
    assertThatThrownBy(
            () ->
                protocols.requireApplicable(
                    context, id, "OOCYTE_TRANSPORT", LocalDate.of(2026, 6, 1)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class);
    assertThat(get(b, path + "/" + id).statusCode()).isEqualTo(404);
  }

  @Test
  void concurrentOwnerAssignmentsSerializeOnTheAnimal() throws Exception {
    var tenant = tenant();
    var animal = animal(tenant);
    var owner = owner(tenant);
    try (var connection = TestDatabase.runtimeConnection();
        var lock =
            connection.prepareStatement(
                "SELECT id FROM animal WHERE organization_id=? AND id=? FOR UPDATE");
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      lock.setObject(1, tenant);
      lock.setObject(2, animal);
      lock.executeQuery().close();
      var first =
          executor.submit(
              () ->
                  post(
                      tenant,
                      "/animals/" + animal + "/ownership",
                      Map.of(
                          "id",
                          IDS.next(),
                          "ownerId",
                          owner,
                          "period",
                          Map.of("from", "2026-01-01"))));
      var second =
          executor.submit(
              () ->
                  post(
                      tenant,
                      "/animals/" + animal + "/ownership",
                      Map.of(
                          "id",
                          IDS.next(),
                          "ownerId",
                          owner,
                          "period",
                          Map.of("from", "2026-01-01"))));
      try {
        awaitLockWaits(2);
      } finally {
        connection.rollback();
      }
      assertThat(
              List.of(
                  first.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode(),
                  second.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(201, 409);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM animal_ownership_assignment WHERE organization_id=? AND animal_id=?",
                  Integer.class,
                  tenant,
                  animal))
          .isEqualTo(1);
    }
  }

  private void awaitLockWaits(int minimum) throws Exception {
    var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      while (System.nanoTime() < deadline) {
        try (var rows =
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'")) {
          rows.next();
          if (rows.getInt(1) >= minimum) return;
        }
        Thread.sleep(20);
      }
    }
    fail("Concurrent commands did not reach the PostgreSQL lock");
  }

  private UUID animal(UUID tenant) throws Exception {
    var id = IDS.next();
    var response = post(tenant, "/animals", Map.of("id", id, "sex", "FEMALE", "name", "Animal"));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return id;
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
