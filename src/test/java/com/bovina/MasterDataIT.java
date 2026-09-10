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
import tools.jackson.databind.JsonNode;
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
  @Autowired com.bovina.parties.application.ClientImportTransactions importTransactions;
  @Autowired com.bovina.parties.application.ClientImports imports;

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
      assertThat(json.readTree(registered.body()).path("version").asLong())
          .isEqualTo(((Number) reuse.get("expectedVersion")).longValue() + 1);
      assertThat(json.readTree(post(tenant, path, key, reuse).body()))
          .isEqualTo(json.readTree(registered.body()));
      reuse.put("expectedVersion", json.readTree(registered.body()).path("version").asLong());
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
    var archived =
        post(
            tenant,
            "/clients/" + id + ":archive",
            Map.of("expectedVersion", reuse.get("expectedVersion")));
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

  @Test
  void clientImportDryRunAndAtomicValidationNeverPartiallyCreateClients() throws Exception {
    var tenant = tenant();
    var batch = IDS.next();
    var client = IDS.next();
    var input =
        importBatch(
            batch, "ATOMIC", List.of(importRow(client, "Valid"), importRow(IDS.next(), " ")));
    var preview = post(tenant, "/clients/imports:dry-run", input);
    assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
    assertThat(preview.body()).contains("VALID", "REJECTED", "INVALID_CLIENT_DETAILS");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM import_batch WHERE id=?", Integer.class, batch))
        .isZero();
    var committed = post(tenant, "/clients/imports", batch, input);
    assertThat(committed.statusCode()).as(committed.body()).isEqualTo(200);
    assertThat(committed.body())
        .contains("NOT_APPLIED", "REJECTED")
        .doesNotContain("\"status\":\"APPLIED\"");
    assertThat(get(tenant, "/clients/" + client).statusCode()).isEqualTo(404);
    assertThat(json.readTree(post(tenant, "/clients/imports", batch, input).body()))
        .isEqualTo(json.readTree(committed.body()));
  }

  @Test
  void partialImportPreservesProvenanceAndReplaysResultsWithoutDuplicatingEffects()
      throws Exception {
    var tenant = tenant();
    var batch = IDS.next();
    var client = IDS.next();
    var input =
        importBatch(
            batch,
            "PARTIAL",
            List.of(importRow(client, "Imported client"), importRow(IDS.next(), " ")));
    var result = post(tenant, "/clients/imports", batch, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    assertThat(result.body()).contains("APPLIED", "REJECTED");
    assertThat(get(tenant, "/clients/" + client).body()).contains("IMPORT", "Imported client");
    assertThat(
            jdbc.queryForObject("SELECT import_batch_id FROM party WHERE id=?", UUID.class, client))
        .isEqualTo(batch);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='IMPORT'",
                Integer.class,
                client))
        .isEqualTo(1);
    assertThat(json.readTree(post(tenant, "/clients/imports", batch, input).body()))
        .isEqualTo(json.readTree(result.body()));
    var changed = importBatch(batch, "PARTIAL", List.of(importRow(IDS.next(), "Changed")));
    assertThat(post(tenant, "/clients/imports", batch, changed).statusCode()).isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party WHERE import_batch_id=?", Integer.class, batch))
        .isEqualTo(1);
  }

  @Test
  void importConstraintFailuresRollbackBeforeRecordingPerItemResults() throws Exception {
    var a = tenant();
    var b = tenant();
    var foreign = owner(b);
    var valid = IDS.next();
    var atomic = IDS.next();
    var atomicInput =
        importBatch(
            atomic,
            "ATOMIC",
            List.of(
                importRow(valid, "Must roll back"), importRow(foreign, "Conflicting identity")));
    var failed = post(a, "/clients/imports", atomic, atomicInput);
    assertThat(failed.statusCode()).as(failed.body()).isEqualTo(200);
    assertThat(failed.body()).contains("ATOMIC_CONSTRAINT_CONFLICT");
    assertThat(get(a, "/clients/" + valid).statusCode()).isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=?", Integer.class, valid))
        .isZero();
    var partial = IDS.next();
    var partialInput =
        importBatch(
            partial,
            "PARTIAL",
            List.of(
                importRow(foreign, "Conflicting identity"),
                importRow(valid, "Valid after rollback")));
    var result = post(a, "/clients/imports", partial, partialInput);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    assertThat(result.body()).contains("CONSTRAINT_CONFLICT", "APPLIED");
    assertThat(get(a, "/clients/" + valid).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM import_item_result WHERE batch_id=?", Integer.class, partial))
        .isEqualTo(2);
  }

  @Test
  void concurrentAtomicImportReplaysTheSameImmutableItemResults() throws Exception {
    var tenant = tenant();
    var batch = IDS.next();
    var client = IDS.next();
    var input = importBatch(batch, "ATOMIC", List.of(importRow(client, "Concurrent import")));
    var barrier = new java.util.concurrent.CyclicBarrier(3);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      for (int i = 0; i < 3; i++)
        futures.add(
            executor.submit(
                () -> {
                  barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                  return post(tenant, "/clients/imports", batch, input);
                }));
      var responses = new ArrayList<JsonNode>();
      for (var future : futures) {
        var result = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        responses.add(json.readTree(result.body()));
      }
      assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(responses.getFirst()));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM party WHERE import_batch_id=?", Integer.class, batch))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='IMPORT'",
                  Integer.class,
                  client))
          .isEqualTo(1);
    }
  }

  @Test
  void productIdentifiersAreScopedAndSearchableWithoutTaxIdDeduplication() throws Exception {
    var a = tenant();
    var b = tenant();
    var first = owner(a);
    var second = owner(a);
    var input = Map.of("id", IDS.next(), "type", "DECLARED_TAX_ID", "value", " 001-A ");
    assertThat(post(a, "/owners/" + first + "/identifiers", input).statusCode()).isEqualTo(201);
    var duplicate = Map.of("id", IDS.next(), "type", "DECLARED_TAX_ID", "value", "001-A");
    assertThat(post(a, "/owners/" + first + "/identifiers", duplicate).statusCode()).isEqualTo(409);
    assertThat(post(a, "/owners/" + second + "/identifiers", duplicate).statusCode())
        .isEqualTo(201);
    assertThat(get(a, "/owners?scope=ANIMAL&q=001-A").body())
        .contains(first.toString(), second.toString());
    assertThat(get(b, "/owners/" + first + "/identifiers").statusCode()).isEqualTo(404);
    assertThat(get(a, "/owners/" + first + "/identifiers").body())
        .doesNotContain("partyId", "organizationId");
  }

  @Test
  void establishmentCapabilitiesAreExplicitAndDoNotCreateDefaultLocations() throws Exception {
    var tenant = tenant();
    var id = IDS.next();
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
    var key = IDS.next();
    var result = post(tenant, "/establishments", key, input);
    assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
    var reordered = new HashMap<String, Object>(input);
    reordered.put("capabilities", List.of("TRANSFER", "EMBRYO_PRODUCTION"));
    assertThat(json.readTree(post(tenant, "/establishments", key, reordered).body()))
        .isEqualTo(json.readTree(result.body()));
    assertThat(get(tenant, "/establishments?size=1").body())
        .contains("EMBRYO_PRODUCTION", "TRANSFER");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operational_location WHERE establishment_id=?",
                Integer.class,
                id))
        .isZero();
    animal(tenant);
    var observed = get(tenant, "/animals?role=DONOR");
    assertThat(observed.statusCode()).isEqualTo(200);
    assertThat(json.readTree(observed.body()).path("items").size()).isZero();
  }

  @Test
  void withdrawingOneProtocolRevisionPreservesItsBytesAndOtherVersions() throws Exception {
    var tenant = tenant();
    var definition = IDS.next();
    var first = IDS.next();
    var second = IDS.next();
    assertThat(
            post(
                    tenant,
                    "/protocol-definitions",
                    Map.of("id", definition, "purpose", "OOCYTE_TRANSPORT", "name", "Transport"))
                .statusCode())
        .isEqualTo(201);
    var path = "/protocol-definitions/" + definition + "/versions";
    var publication =
        Map.of(
            "id",
            first,
            "revision",
            "1",
            "effectivePeriod",
            Map.of("from", "2026-01-01"),
            "contentReference",
            "reference/1",
            "checksum",
            "a".repeat(64));
    var original = post(tenant, path, publication);
    assertThat(original.statusCode()).isEqualTo(201);
    var next = new HashMap<String, Object>(publication);
    next.put("id", second);
    next.put("revision", "2");
    assertThat(post(tenant, path, next).statusCode()).isEqualTo(201);
    var key = IDS.next();
    var reason = Map.of("reason", "Superseded operational instruction");
    var withdrawal = post(tenant, path + "/" + first + ":deactivate", key, reason);
    assertThat(withdrawal.statusCode()).as(withdrawal.body()).isEqualTo(200);
    assertThat(json.readTree(post(tenant, path + "/" + first + ":deactivate", key, reason).body()))
        .isEqualTo(json.readTree(withdrawal.body()));
    assertThat(json.readTree(get(tenant, path + "/" + first).body()))
        .isEqualTo(json.readTree(original.body()));
    var context = context(tenant);
    assertThatThrownBy(
            () ->
                protocols.requireApplicable(
                    context, first, "OOCYTE_TRANSPORT", LocalDate.of(2026, 2, 1)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("PROTOCOL_VERSION_WITHDRAWN");
    assertThat(
            protocols
                .requireApplicable(context, second, "OOCYTE_TRANSPORT", LocalDate.of(2026, 2, 1))
                .id())
        .isEqualTo(second);
  }

  @Test
  void partialImportResumesCommittedItemsAfterAnInterruptedBatch() throws Exception {
    var tenant = tenant();
    var first = IDS.next();
    var second = IDS.next();
    var id = IDS.next();
    var input =
        importBatch(id, "PARTIAL", List.of(importRow(first, "First"), importRow(second, "Second")));
    var batch =
        json.readValue(
            json.writeValueAsString(input), com.bovina.parties.domain.ClientImportBatch.class);
    var context = context(tenant);
    importTransactions.bind(context, batch);
    importTransactions.partialItem(context, batch, batch.items().getFirst());
    var result = imports.commit(context, id, batch);
    assertThat(result.items())
        .allSatisfy(
            item ->
                assertThat(item.status())
                    .isEqualTo(com.bovina.parties.domain.ClientImportBatch.ItemStatus.APPLIED));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party WHERE import_batch_id=?", Integer.class, id))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='IMPORT'",
                Integer.class,
                first))
        .isEqualTo(1);
  }

  @Test
  void masterDataRbacIsEnforcedBeyondTheController() throws Exception {
    var tenant = tenant();
    var reader = "reader-" + IDS.next();
    var grant =
        post(
            tenant,
            "/memberships",
            Map.of("id", IDS.next(), "subject", reader, "role", "READ_ONLY"));
    assertThat(grant.statusCode()).as(grant.body()).isEqualTo(201);
    var context =
        access.resolve(
            new com.bovina.identity.application.AuthenticatedIdentity(
                TOKENS.issuer().toString(), reader),
            tenant,
            IDS.next());
    assertThatThrownBy(
            () ->
                protocols.register(
                    context,
                    IDS.next(),
                    new com.bovina.protocols.application.Protocols.Register(
                        IDS.next(), "OOCYTE_TRANSPORT", "Denied", null)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    var batch =
        json.readValue(
            json.writeValueAsString(
                importBatch(IDS.next(), "ATOMIC", List.of(importRow(IDS.next(), "Denied")))),
            com.bovina.parties.domain.ClientImportBatch.class);
    assertThatThrownBy(() -> imports.commit(context, batch.batchId(), batch))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/animals"))
            .header(
                "Authorization",
                "Bearer "
                    + TOKENS.token(
                        reader,
                        TOKENS.issuer().toString(),
                        "bovina-test",
                        Instant.now().plusSeconds(600)))
            .header("X-Organization-ID", tenant.toString())
            .header("Idempotency-Key", IDS.next().toString())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("id", IDS.next(), "sex", "FEMALE"))))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(403);
    }
  }

  @Test
  void compoundForeignKeysRejectCrossTenantMasterDataEvenWithoutApplicationChecks()
      throws Exception {
    var a = tenant();
    var b = tenant();
    var ownerA = owner(a);
    var ownerB = owner(b);
    var animalA = animal(a);
    var animalB = animal(b);
    var establishmentA = establishment(a);
    var establishmentB = establishment(b);
    var location = IDS.next();
    assertThat(
            post(
                    a,
                    "/establishments/" + establishmentA + "/operational-locations",
                    Map.of("id", location, "name", "Local", "type", "LAB"))
                .statusCode())
        .isEqualTo(201);
    var breed = IDS.next();
    assertThat(post(b, "/breeds", Map.of("id", breed, "name", "Declared breed")).statusCode())
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
                  + IDS.next()
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
                  + IDS.next()
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

  private com.bovina.platform.application.ExecutionContext context(UUID tenant) {
    return access.resolve(
        new com.bovina.identity.application.AuthenticatedIdentity(
            TOKENS.issuer().toString(), "master-data-bootstrap"),
        tenant,
        IDS.next());
  }

  private Map<String, Object> importRow(UUID id, String name) {
    return Map.of(
        "itemId",
        IDS.next(),
        "id",
        id,
        "type",
        "PERSON",
        "displayName",
        name,
        "occurredAt",
        Instant.EPOCH);
  }

  private Map<String, Object> importBatch(UUID id, String mode, List<Map<String, Object>> rows) {
    return Map.of("batchId", id, "mode", mode, "items", rows);
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
