package com.bovina.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import com.bovina.support.integration.ClientApiIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CommandIdempotencyIT extends ClientApiIntegrationTest {
  @Test
  void payloadConflictDoesNotReuseResultAndKeysAreTenantScoped() throws Exception {
    var a = tenantWithRole("OPERATOR");
    var b = tenantWithRole("OPERATOR");
    var key = id();
    var id = id();
    assertThat(post(a, key, clientBody(id, "A")).statusCode()).isEqualTo(201);
    var conflict = post(a, key, clientBody(id, "B"));
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(json.readTree(conflict.body()).path("code").asString())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(post(b, key, clientBody(id(), "B")).statusCode()).isEqualTo(201);
    assertThat(count("idempotent_command", "idempotency_key", key)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT display_name FROM party WHERE id=?", String.class, id))
        .isEqualTo("A");
  }

  @Test
  void concurrentIdenticalRequestsWaitAndExecuteExactlyOnce() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var id = id();
    var key = id();
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
    var tenant = tenantWithRole("OPERATOR");
    var key = id();
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var a =
          workers.submit(
              () -> {
                start.await();
                return post(tenant, key, clientBody(id(), "A"));
              });
      var b =
          workers.submit(
              () -> {
                start.await();
                return post(tenant, key, clientBody(id(), "B"));
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
  void constraintFailureEndsTransactionAndRetryUsesFreshTransaction() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var id = id();
    assertThat(post(tenant, id(), clientBody(id, "First")).statusCode()).isEqualTo(201);
    var failedKey = id();
    assertThat(post(tenant, failedKey, clientBody(id, "Duplicate")).statusCode()).isEqualTo(409);
    assertThat(count("idempotent_command", "idempotency_key", failedKey)).isZero();
    assertThat(post(tenant, failedKey, clientBody(id(), "Retry")).statusCode()).isEqualTo(201);
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
    var tenant = tenantWithRole("OPERATOR");
    var key = id();
    var id = id();
    try (var connection = TestDatabase.runtimeConnection();
        var statement =
            connection.prepareStatement(
                """
        INSERT INTO idempotent_command(id,organization_id,command_type,idempotency_key,actor_id,request_hash,status,created_at)
        VALUES (?,?,'CREATE_CLIENT_V1',?,?,?,'PROCESSING',now())
        """);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      statement.setObject(1, id());
      statement.setObject(2, tenant.id());
      statement.setObject(3, key);
      statement.setObject(4, tenant.actor());
      statement.setString(5, "0".repeat(64));
      statement.executeUpdate();
      var retry = workers.submit(() -> post(tenant, key, clientBody(id, "After disconnect")));
      try {
        awaitBlockedStatements("idempotent_command", 1);
      } finally {
        connection.abort(Runnable::run);
      }
      assertThat(retry.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    }
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
  }

  @Test
  void idempotencyDoesNotGiveAnotherActorTheOriginalResult() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var key = id();
    var body = clientBody(id(), "Owned command");
    assertThat(post(tenant, key, body).statusCode()).isEqualTo(201);
    var other =
        request("POST", "/api/v1/clients", token("integration-bootstrap"), tenant.id(), key, body);
    assertThat(other.statusCode()).isEqualTo(409);
    assertThat(other.body()).doesNotContain("Owned command", tenant.actor().toString());
    var canonical = post(tenant, key, " \n" + body + "\n ");
    assertThat(canonical.statusCode()).isEqualTo(201);
  }
}
