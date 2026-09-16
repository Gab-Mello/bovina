package com.bovina.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.audit.application.AuditEvent;
import com.bovina.audit.application.AuditRecorder;
import com.bovina.parties.application.CreateClientService;
import com.bovina.support.TestDatabase;
import com.bovina.support.integration.ClientApiIntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AuditTransactionIT extends ClientApiIntegrationTest {
  @Autowired private CreateClientService createClient;
  @Autowired private AuditRecorder audit;
  @Autowired private PlatformTransactionManager transactions;

  @Test
  void auditFailureRollsBackClientRoleClaimAndAllowsRetry() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var id = id();
    var key = id();
    var body = clientBody(id, "Atomic");
    try (var owner = migrationConnection();
        var statement = owner.createStatement()) {
      statement.execute(
          "ALTER TABLE audit_event ADD CONSTRAINT reject_client_audit CHECK (entity_id <> '"
              + id
              + "')");
      try {
        var failed = post(tenant, key, body);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(409);
        assertThat(failed.body()).doesNotContain("reject_client_audit", "audit_event", "Atomic");
        assertThat(count("party", "id", id)).isZero();
        assertThat(count("party_role", "party_id", id)).isZero();
        assertThat(count("audit_event", "entity_id", id)).isZero();
        assertThat(count("idempotent_command", "idempotency_key", key)).isZero();
      } finally {
        statement.execute("ALTER TABLE audit_event DROP CONSTRAINT reject_client_audit");
      }
    }
    assertThat(post(tenant, key, body).statusCode()).isEqualTo(201);
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
  }

  @Test
  void auditIsAppendOnlyAndCannotBeWrittenOutsideBusinessTransaction() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var id = id();
    assertThat(post(tenant, id(), clientBody(id, "A")).statusCode()).isEqualTo(201);
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
                        id(),
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
    var rolledBackId = id();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        tx -> {
                          createClient.create(
                              context(tenant), command(rolledBackId, id(), "Rollback"));
                          throw new IllegalStateException("Simulated interrupted command");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("party", "id", rolledBackId)).isZero();
    assertThat(count("audit_event", "entity_id", rolledBackId)).isZero();
  }
}
