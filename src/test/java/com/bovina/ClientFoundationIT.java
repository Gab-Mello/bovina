package com.bovina;

import static org.assertj.core.api.Assertions.*;

import com.bovina.audit.application.*;
import com.bovina.identity.application.*;
import com.bovina.parties.application.*;
import com.bovina.parties.domain.ClientType;
import com.bovina.parties.infrastructure.PartyRepository;
import com.bovina.platform.application.*;
import com.bovina.support.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientFoundationIT {
  private static final TrustedTokens TOKENS = new TrustedTokens();
  private static final StableIds IDS = new StableIds();
  private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired TenantAccess access;
  @Autowired PartyRepository parties;
  @Autowired CreateClientService createClient;
  @Autowired GetClient getClient;
  @Autowired AuditRecorder audit;
  @Autowired PlatformTransactionManager transactions;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
    registry.add("bovina.security.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.security.jwk-set-uri", () -> TOKENS.jwks().toString());
    registry.add("bovina.bootstrap.enabled", () -> true);
    registry.add("bovina.bootstrap.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.bootstrap.subject", () -> "bootstrap");
    registry.add("bovina.cors.allowed-origins", () -> "https://ui.invalid");
  }

  @AfterAll
  static void closeKeys() {
    TOKENS.close();
  }

  @Test
  void clientRoundTripIsTenantScopedAuditedAndIdempotent() throws Exception {
    var tenant = tenant("OPERATOR");
    var id = IDS.next();
    var key = IDS.next();
    var body = clientBody(id, "Client A");
    var created = post(tenant, key, body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var view = json.readTree(created.body());
    assertThat(view.path("id").asString()).isEqualTo(id.toString());
    assertThat(view.path("version").asLong()).isZero();
    assertThat(view.path("originType").asString()).isEqualTo("MANUAL");
    assertThat(view.path("recordedBy").asString()).isEqualTo(tenant.actor().toString());
    assertThat(Instant.parse(view.path("recordedAt").asString())).isAfter(OCCURRED);
    assertThat(created.body()).doesNotContain("Party", "organizationId", "roles");
    assertThat(created.headers().firstValue("Location")).hasValue("/api/v1/clients/" + id);
    var replay = post(tenant, key, body);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(json.readTree(replay.body())).isEqualTo(view);
    var read = request("GET", "/api/v1/clients/" + id, tenant.token(), tenant.id(), null, null);
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(json.readTree(read.body())).isEqualTo(view);
    assertThat(read.headers().firstValue("ETag")).isEqualTo(created.headers().firstValue("ETag"));
    assertThat(count("party", "id", id)).isEqualTo(1);
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
    assertThat(count("idempotent_command", "idempotency_key", key)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM party_role WHERE organization_id=? AND party_id=?",
                String.class,
                tenant.id(),
                id))
        .isEqualTo("CLIENT");
    assertThat(
            jdbc.queryForObject("SELECT occurred_at FROM party WHERE id=?", Timestamp.class, id)
                .toInstant())
        .isEqualTo(OCCURRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT uuid_extract_version(id) FROM party WHERE id=?", Integer.class, id))
        .isEqualTo(7);
    var event = jdbc.queryForMap("SELECT * FROM audit_event WHERE entity_id=?", id);
    assertThat(event)
        .containsEntry("organization_id", tenant.id())
        .containsEntry("actor_id", tenant.actor())
        .containsEntry("action", "CREATE")
        .containsEntry("entity_type", "CLIENT")
        .containsEntry("entity_version", 0L);
    assertThat(event.get("correlation_id").toString())
        .isEqualTo(created.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertThat(event.values().toString()).doesNotContain("Client A", tenant.token());
  }

  @Test
  void crossTenantAccessFailsAtApiApplicationAndRepository() throws Exception {
    var a = tenant("OPERATOR");
    var b = tenant("OPERATOR");
    var id = IDS.next();
    assertThat(post(a, IDS.next(), clientBody(id, "Private A")).statusCode()).isEqualTo(201);
    var foreign = request("GET", "/api/v1/clients/" + id, b.token(), b.id(), null, null);
    var missing = request("GET", "/api/v1/clients/" + IDS.next(), b.token(), b.id(), null, null);
    assertThat(foreign.statusCode()).isEqualTo(404);
    assertThat(json.readTree(foreign.body()).path("code").asString())
        .isEqualTo(json.readTree(missing.body()).path("code").asString());
    assertThat(foreign.body()).doesNotContain("Private A", a.id().toString());
    assertThat(parties.findByOrganizationIdAndId(b.id(), id)).isEmpty();
    assertThatThrownBy(() -> getClient.get(context(b), id))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("CLIENT_NOT_FOUND");
    var forged =
        new ExecutionContext(b.id(), a.actor(), Set.of("client:read", "client:create"), IDS.next());
    assertThatThrownBy(() -> getClient.get(forged, id))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    assertThat(request("GET", "/api/v1/clients/" + id, b.token(), a.id(), null, null).statusCode())
        .isEqualTo(403);
    assertThat(request("GET", "/api/v1/parties/" + id, a.token(), a.id(), null, null).statusCode())
        .isEqualTo(404);
  }

  @Test
  void readOnlyAndRevokedMembershipCannotWriteEvenWithValidJwt() throws Exception {
    var reader = tenant("READ_ONLY");
    assertThat(post(reader, IDS.next(), clientBody(IDS.next(), "Blocked")).statusCode())
        .isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships",
                    reader.token(),
                    reader.id(),
                    null,
                    json.writeValueAsString(
                        Map.of(
                            "id", IDS.next(), "subject", "attempted-admin", "role", "ORG_ADMIN")))
                .statusCode())
        .isEqualTo(403);
    assertThatThrownBy(
            () -> createClient.create(context(reader), command(IDS.next(), IDS.next(), "Blocked")))
        .isInstanceOf(ApplicationFailure.class);
    var forgedPermissions =
        new ExecutionContext(reader.id(), reader.actor(), Set.of("client:create"), IDS.next());
    assertThatThrownBy(
            () ->
                createClient.create(forgedPermissions, command(IDS.next(), IDS.next(), "Blocked")))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    var revoke = json.writeValueAsString(Map.of("expectedVersion", 0, "reason", "Access removed"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + reader.membership() + ":revoke",
                    token("bootstrap"),
                    reader.id(),
                    null,
                    revoke)
                .statusCode())
        .isEqualTo(204);
    assertThat(request("GET", "/api/v1/me", reader.token(), reader.id(), null, null).statusCode())
        .isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + reader.membership() + ":revoke",
                    token("bootstrap"),
                    reader.id(),
                    null,
                    revoke)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM audit_event WHERE entity_id=? AND action='REVOKE'",
                String.class,
                reader.membership()))
        .isEqualTo("Access removed");
  }

  @Test
  void unknownIdentityAndCrossTenantMembershipAdministrationAreDenied() throws Exception {
    var a = tenant("OPERATOR");
    var b = tenant("OPERATOR");
    assertThat(request("GET", "/api/v1/me", token("unregistered"), a.id(), null, null).statusCode())
        .isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    "/api/v1/bootstrap/organizations",
                    a.token(),
                    null,
                    null,
                    organizationBody(IDS.next()))
                .statusCode())
        .isEqualTo(403);
    var revoke = json.writeValueAsString(Map.of("expectedVersion", 0, "reason", "Attempt"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + a.membership() + ":revoke",
                    token("bootstrap"),
                    b.id(),
                    null,
                    revoke)
                .statusCode())
        .isEqualTo(404);
    assertThat(request("GET", "/api/v1/me", token("bootstrap"), null, null, null).statusCode())
        .isEqualTo(422);
    assertThat(request("GET", "/api/v1/me", token("bootstrap"), a.id(), null, null).statusCode())
        .isEqualTo(200);
  }

  @Test
  void bodyCannotAssignTenantOriginActorOrPersistenceState() throws Exception {
    var tenant = tenant("OPERATOR");
    for (var field : List.of("organizationId", "originType", "recordedBy", "status", "roles")) {
      var body = json.readTree(clientBody(IDS.next(), "A")).deepCopy();
      ((tools.jackson.databind.node.ObjectNode) body).put(field, "forged");
      assertThat(post(tenant, IDS.next(), body.toString()).statusCode()).as(field).isEqualTo(400);
    }
    assertThat(post(tenant, null, clientBody(IDS.next(), "A")).statusCode()).isEqualTo(400);
    assertThat(post(tenant, IDS.next(), clientBody(UUID.randomUUID(), "A")).statusCode())
        .isEqualTo(422);
    assertThat(post(tenant, IDS.next(), clientBody(IDS.next(), " ")).statusCode()).isEqualTo(400);
    assertThat(
            request(
                    "POST",
                    "/api/v1/clients",
                    null,
                    tenant.id(),
                    IDS.next(),
                    clientBody(IDS.next(), "A"))
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void payloadConflictDoesNotReuseResultAndKeysAreTenantScoped() throws Exception {
    var a = tenant("OPERATOR");
    var b = tenant("OPERATOR");
    var key = IDS.next();
    var id = IDS.next();
    assertThat(post(a, key, clientBody(id, "A")).statusCode()).isEqualTo(201);
    var conflict = post(a, key, clientBody(id, "B"));
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(json.readTree(conflict.body()).path("code").asString())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(post(b, key, clientBody(IDS.next(), "B")).statusCode()).isEqualTo(201);
    assertThat(count("idempotent_command", "idempotency_key", key)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT display_name FROM party WHERE id=?", String.class, id))
        .isEqualTo("A");
  }

  @Test
  void concurrentIdenticalRequestsWaitAndExecuteExactlyOnce() throws Exception {
    var tenant = tenant("OPERATOR");
    var id = IDS.next();
    var key = IDS.next();
    var body = clientBody(id, "Concurrent");
    try (var lock = TestDatabase.runtimeConnection();
        var statement = lock.createStatement();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      lock.setAutoCommit(false);
      statement.execute("LOCK TABLE party IN SHARE MODE");
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++) futures.add(workers.submit(() -> post(tenant, key, body)));
      try {
        awaitBlockedStatements(2);
      } finally {
        lock.rollback();
      }
      JsonNode expected = null;
      for (var future : futures) {
        var response = future.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var actual = json.readTree(response.body());
        if (expected == null) expected = actual;
        assertThat(actual).isEqualTo(expected);
      }
    }
    assertThat(count("party", "id", id)).isEqualTo(1);
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
    assertThat(count("idempotent_command", "idempotency_key", key)).isEqualTo(1);
  }

  @Test
  void concurrentDifferentPayloadsHaveOneWinner() throws Exception {
    var tenant = tenant("OPERATOR");
    var key = IDS.next();
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var a =
          workers.submit(
              () -> {
                start.await();
                return post(tenant, key, clientBody(IDS.next(), "A"));
              });
      var b =
          workers.submit(
              () -> {
                start.await();
                return post(tenant, key, clientBody(IDS.next(), "B"));
              });
      start.countDown();
      assertThat(
              List.of(
                  a.get(15, TimeUnit.SECONDS).statusCode(),
                  b.get(15, TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(count("idempotent_command", "idempotency_key", key)).isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackClientRoleClaimAndAllowsRetry() throws Exception {
    var tenant = tenant("OPERATOR");
    var id = IDS.next();
    var key = IDS.next();
    var body = clientBody(id, "Atomic");
    try (var owner = migrationConnection();
        var statement = owner.createStatement()) {
      statement.execute(
          "ALTER TABLE audit_event ADD CONSTRAINT phase1_reject_audit CHECK (entity_id <> '"
              + id
              + "')");
      try {
        var failed = post(tenant, key, body);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(409);
        assertThat(failed.body()).doesNotContain("phase1_reject_audit", "audit_event", "Atomic");
        assertThat(count("party", "id", id)).isZero();
        assertThat(count("party_role", "party_id", id)).isZero();
        assertThat(count("audit_event", "entity_id", id)).isZero();
        assertThat(count("idempotent_command", "idempotency_key", key)).isZero();
      } finally {
        statement.execute("ALTER TABLE audit_event DROP CONSTRAINT phase1_reject_audit");
      }
    }
    assertThat(post(tenant, key, body).statusCode()).isEqualTo(201);
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
  }

  @Test
  void constraintFailureEndsTransactionAndRetryUsesFreshTransaction() throws Exception {
    var tenant = tenant("OPERATOR");
    var id = IDS.next();
    assertThat(post(tenant, IDS.next(), clientBody(id, "First")).statusCode()).isEqualTo(201);
    var failedKey = IDS.next();
    assertThat(post(tenant, failedKey, clientBody(id, "Duplicate")).statusCode()).isEqualTo(409);
    assertThat(count("idempotent_command", "idempotency_key", failedKey)).isZero();
    assertThat(post(tenant, failedKey, clientBody(IDS.next(), "Retry")).statusCode())
        .isEqualTo(201);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO party_role VALUES ('"
                          + tenant.id()
                          + "','"
                          + id
                          + "','CLIENT')"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23505");
      assertThatThrownBy(() -> statement.executeQuery("SELECT 1"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("25P02");
      connection.rollback();
      assertThat(statement.executeQuery("SELECT 1").next()).isTrue();
      connection.rollback();
    }
  }

  @Test
  void disconnectedUncommittedClaimDoesNotStrandRetry() throws Exception {
    var tenant = tenant("OPERATOR");
    var key = IDS.next();
    var id = IDS.next();
    try (var connection = TestDatabase.runtimeConnection();
        var statement =
            connection.prepareStatement(
                """
        INSERT INTO idempotent_command(id,organization_id,command_type,idempotency_key,actor_id,request_hash,status,created_at)
        VALUES (?,?,'CREATE_CLIENT_V1',?,?,?,'PROCESSING',now())
        """);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      statement.setObject(1, IDS.next());
      statement.setObject(2, tenant.id());
      statement.setObject(3, key);
      statement.setObject(4, tenant.actor());
      statement.setString(5, "0".repeat(64));
      statement.executeUpdate();
      var retry = workers.submit(() -> post(tenant, key, clientBody(id, "After disconnect")));
      try {
        awaitBlockedStatements(1);
      } finally {
        connection.abort(Runnable::run);
      }
      assertThat(retry.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    }
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
  }

  @Test
  void compoundForeignKeysPreventCrossTenantAssociations() throws Exception {
    var a = tenant("OPERATOR");
    var b = tenant("OPERATOR");
    var id = IDS.next();
    var key = IDS.next();
    assertThat(post(a, key, clientBody(id, "A")).statusCode()).isEqualTo(201);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var sql :
          List.of(
              "INSERT INTO party_role VALUES ('" + b.id() + "','" + id + "','CLIENT')",
              "UPDATE party SET recorded_by='" + b.actor() + "' WHERE id='" + id + "'",
              "UPDATE idempotent_command SET organization_id='"
                  + b.id()
                  + "',actor_id='"
                  + b.actor()
                  + "' WHERE idempotency_key='"
                  + key
                  + "'")) {
        assertThatThrownBy(() -> statement.execute(sql))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("23503");
      }
      assertThatThrownBy(
              () ->
                  statement.execute("UPDATE party SET organization_id=NULL WHERE id='" + id + "'"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23502");
    }
  }

  @Test
  void auditIsAppendOnlyAndCannotBeWrittenOutsideBusinessTransaction() throws Exception {
    var tenant = tenant("OPERATOR");
    var id = IDS.next();
    assertThat(post(tenant, IDS.next(), clientBody(id, "A")).statusCode()).isEqualTo(201);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var sql :
          List.of(
              "UPDATE audit_event SET action='CHANGE' WHERE entity_id='" + id + "'",
              "DELETE FROM audit_event WHERE entity_id='" + id + "'",
              "TRUNCATE audit_event")) {
        assertThatThrownBy(() -> statement.execute(sql))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("42501");
      }
    }
    assertThatThrownBy(
            () ->
                audit.record(
                    new AuditEvent(
                        IDS.next(),
                        context(tenant),
                        Instant.now(),
                        "CREATE",
                        "CLIENT",
                        id,
                        0L,
                        null,
                        null,
                        "ACTIVE")))
        .isInstanceOf(IllegalTransactionStateException.class);
    var rolledBackId = IDS.next();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        tx -> {
                          createClient.create(
                              context(tenant), command(rolledBackId, IDS.next(), "Rollback"));
                          throw new IllegalStateException("Simulated interrupted command");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("party", "id", rolledBackId)).isZero();
    assertThat(count("audit_event", "entity_id", rolledBackId)).isZero();
  }

  @Test
  void expiredMembershipDisabledUserAndSuspendedOrganizationAreDenied() throws Exception {
    var tenant = tenant("OPERATOR");
    jdbc.update(
        "UPDATE organization_membership SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",
        tenant.membership());
    assertThat(request("GET", "/api/v1/me", tenant.token(), tenant.id(), null, null).statusCode())
        .isEqualTo(403);
    jdbc.update(
        "UPDATE organization_membership SET valid_until=NULL WHERE id=?", tenant.membership());
    jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", tenant.actor());
    assertThat(request("GET", "/api/v1/me", tenant.token(), tenant.id(), null, null).statusCode())
        .isEqualTo(403);
    jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?", tenant.actor());
    jdbc.update("UPDATE organization SET status='SUSPENDED' WHERE id=?", tenant.id());
    assertThat(request("GET", "/api/v1/me", tenant.token(), tenant.id(), null, null).statusCode())
        .isEqualTo(403);
  }

  @Test
  void corsAllowsOnlyConfiguredUiWithoutSessionCookies() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      for (var origin : List.of("https://ui.invalid", "https://attacker.invalid")) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/clients"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header(
                        "Access-Control-Request-Headers",
                        "authorization,idempotency-key,x-organization-id")
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(origin.contains("attacker") ? 403 : 200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        if (!origin.contains("attacker"))
          assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
      }
    }
  }

  @Test
  void identityPairAndMembershipAreUniqueAndInvalidGrantsAreRejected() throws Exception {
    var tenant = tenant("OPERATOR");
    var duplicate =
        json.writeValueAsString(
            Map.of("id", IDS.next(), "subject", tenant.subject(), "role", "OPERATOR"));
    assertThat(
            request("POST", "/api/v1/memberships", token("bootstrap"), tenant.id(), null, duplicate)
                .statusCode())
        .isEqualTo(409);
    var expired =
        json.writeValueAsString(
            Map.of(
                "id",
                IDS.next(),
                "subject",
                "expired-" + IDS.next(),
                "role",
                "OPERATOR",
                "validUntil",
                Instant.EPOCH));
    assertThat(
            request("POST", "/api/v1/memberships", token("bootstrap"), tenant.id(), null, expired)
                .statusCode())
        .isEqualTo(422);
    var noVersion = json.writeValueAsString(Map.of("reason", "Missing version"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + tenant.membership() + ":revoke",
                    token("bootstrap"),
                    tenant.id(),
                    null,
                    noVersion)
                .statusCode())
        .isEqualTo(400);
    jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", tenant.actor());
    assertThat(
            request("POST", "/api/v1/memberships", token("bootstrap"), tenant.id(), null, duplicate)
                .statusCode())
        .isEqualTo(403);
    try (var connection = TestDatabase.runtimeConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO user_account(id,issuer,subject,status,created_at) VALUES (?,?,?,'ACTIVE',now())")) {
      statement.setObject(1, IDS.next());
      statement.setString(2, TOKENS.issuer().toString());
      statement.setString(3, tenant.subject());
      assertThatThrownBy(statement::executeUpdate)
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23505");
      statement.setString(2, "https://other-issuer.invalid");
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  @Test
  void idempotencyDoesNotGiveAnotherActorTheOriginalResult() throws Exception {
    var tenant = tenant("OPERATOR");
    var key = IDS.next();
    var body = clientBody(IDS.next(), "Owned command");
    assertThat(post(tenant, key, body).statusCode()).isEqualTo(201);
    var other = request("POST", "/api/v1/clients", token("bootstrap"), tenant.id(), key, body);
    assertThat(other.statusCode()).isEqualTo(409);
    assertThat(other.body()).doesNotContain("Owned command", tenant.actor().toString());
    var canonical = post(tenant, key, " \n" + body + "\n ");
    assertThat(canonical.statusCode()).isEqualTo(201);
  }

  private Tenant tenant(String role) throws Exception {
    var id = IDS.next();
    var subject = "operator-" + IDS.next();
    var member = IDS.next();
    var created =
        request(
            "POST",
            "/api/v1/bootstrap/organizations",
            token("bootstrap"),
            null,
            null,
            organizationBody(id));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var granted =
        request(
            "POST",
            "/api/v1/memberships",
            token("bootstrap"),
            id,
            null,
            json.writeValueAsString(Map.of("id", member, "subject", subject, "role", role)));
    assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);
    var jwt = token(subject);
    var me = request("GET", "/api/v1/me", jwt, id, null, null);
    assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
    return new Tenant(
        id,
        UUID.fromString(json.readTree(me.body()).path("actorId").asString()),
        subject,
        jwt,
        member);
  }

  private String organizationBody(UUID id) {
    return json.writeValueAsString(
        Map.of(
            "id",
            id,
            "legalName",
            "Test organization",
            "taxId",
            "test-" + id.toString().substring(0, 8),
            "timezone",
            "America/Sao_Paulo"));
  }

  private String clientBody(UUID id, String name) {
    return json.writeValueAsString(
        Map.of("id", id, "type", "PERSON", "displayName", name, "occurredAt", OCCURRED));
  }

  private CreateClient command(UUID id, UUID key, String name) {
    return new CreateClient(id, ClientType.PERSON, name, new CommandMetadata(key, OCCURRED));
  }

  private ExecutionContext context(Tenant tenant) {
    return access.resolve(
        new AuthenticatedIdentity(TOKENS.issuer().toString(), tenant.subject()),
        tenant.id(),
        IDS.next());
  }

  private String token(String subject) throws Exception {
    return TOKENS.token(
        subject, TOKENS.issuer().toString(), "bovina-test", Instant.now().plusSeconds(600));
  }

  private HttpResponse<String> post(Tenant tenant, UUID key, String body) throws Exception {
    return request("POST", "/api/v1/clients", tenant.token(), tenant.id(), key, body);
  }

  private HttpResponse<String> request(
      String method, String path, String token, UUID tenant, UUID key, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (tenant != null) request.header("X-Organization-ID", tenant.toString());
    if (key != null) request.header("Idempotency-Key", key.toString());
    if (body != null) request.header("Content-Type", "application/json");
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
  }

  private Connection migrationConnection() throws SQLException {
    return DriverManager.getConnection(
        TestDatabase.POSTGRES.getJdbcUrl(), "bovina_migration", TestDatabase.MIGRATION_PASSWORD);
  }

  private void awaitBlockedStatements(int minimum) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
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
    fail("Concurrent commands did not reach a PostgreSQL lock wait");
  }

  private record Tenant(UUID id, UUID actor, String subject, String token, UUID membership) {}
}
