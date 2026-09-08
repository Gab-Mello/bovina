package com.bovina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import fixture.persistence.DatabaseRecord;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DatabaseSemanticsIT {
  private static SessionFactory sessions;
  private static Flyway flyway;

  @BeforeAll
  static void migrateAndValidate() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                TestDatabase.POSTGRES.getJdbcUrl(),
                TestDatabase.POSTGRES.getUsername(),
                TestDatabase.POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA baseline_probe AUTHORIZATION bovina_migration");
    }
    flyway =
        Flyway.configure()
            .dataSource(
                TestDatabase.POSTGRES.getJdbcUrl(),
                "bovina_migration",
                TestDatabase.MIGRATION_PASSWORD)
            .schemas("baseline_probe")
            .defaultSchema("baseline_probe")
            .locations("classpath:database/migration")
            .load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    sessions =
        new Configuration()
            .addAnnotatedClass(DatabaseRecord.class)
            .setProperty("hibernate.connection.url", TestDatabase.POSTGRES.getJdbcUrl())
            .setProperty("hibernate.connection.username", "bovina_runtime")
            .setProperty("hibernate.connection.password", TestDatabase.RUNTIME_PASSWORD)
            .setProperty("hibernate.hbm2ddl.auto", "validate")
            .setProperty("hibernate.jdbc.time_zone", "UTC")
            .buildSessionFactory();
  }

  @AfterAll
  static void close() {
    if (sessions != null) sessions.close();
  }

  @Test
  void migrationIsRepeatableWithoutSchemaGeneration() {
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void assignedUuidAndTemporalTypesRoundTripThroughHibernate() throws Exception {
    var organization = UUID.randomUUID();
    var parent = UUID.randomUUID();
    insertParent(organization, parent);
    var id = UUID.fromString("01992678-9600-7000-8000-000000000001");
    try (var session = sessions.openSession()) {
      var transaction = session.beginTransaction();
      session.persist(new DatabaseRecord(id, organization, parent));
      transaction.commit();
    }
    try (var session = sessions.openSession()) {
      var loaded = session.find(DatabaseRecord.class, id);
      assertThat(loaded.id()).isEqualTo(id);
      assertThat(loaded.id().version()).isEqualTo(7);
      assertThat(loaded.occurredAt()).isEqualTo(Instant.parse("2026-09-08T12:34:56.123456Z"));
      assertThat(loaded.civilDate()).isEqualTo(LocalDate.of(2026, 9, 8));
      assertThat(loaded.localTime()).isEqualTo(LocalDateTime.of(2026, 9, 8, 9, 34, 56));
      assertThat(loaded.zoneId()).isEqualTo("America/Sao_Paulo");
      assertThat(loaded.version()).isZero();
    }
    try (var connection = connection();
        var statement = connection.createStatement()) {
      statement.execute("SET TIME ZONE 'Pacific/Auckland'");
      try (var result =
          statement.executeQuery(
              "SELECT pg_typeof(id)::text, occurred_at FROM baseline_probe.fixture_record WHERE id = '"
                  + id
                  + "'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("uuid");
        assertThat(result.getTimestamp(2).toInstant())
            .isEqualTo(Instant.parse("2026-09-08T12:34:56.123456Z"));
      }
    }
  }

  @Test
  void databaseRejectsCrossTenantForeignKeysAndInvalidLocalValues() throws Exception {
    var organization = UUID.randomUUID();
    var parent = UUID.randomUUID();
    insertParent(organization, parent);
    assertSqlState("23503", recordInsert(UUID.randomUUID(), UUID.randomUUID(), parent, 1));
    assertSqlState("23514", recordInsert(UUID.randomUUID(), organization, parent, -1));
    assertSqlState(
        "23502",
        "INSERT INTO baseline_probe.fixture_parent (id) VALUES ('" + UUID.randomUUID() + "')");
  }

  @Test
  void partialAndExpressionIndexesEnforceTheirExactScope() throws Exception {
    var organization = UUID.randomUUID();
    var resource = UUID.randomUUID();
    var first = UUID.randomUUID();
    execute(reservationInsert(first, organization, resource));
    assertSqlState("23505", reservationInsert(UUID.randomUUID(), organization, resource));
    execute(
        "UPDATE baseline_probe.fixture_reservation SET active = false WHERE id = '" + first + "'");
    execute(reservationInsert(UUID.randomUUID(), organization, resource));
    execute(
        "INSERT INTO baseline_probe.fixture_identifier VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + organization
            + "', NULL, 'AbC')");
    assertSqlState(
        "23505",
        "INSERT INTO baseline_probe.fixture_identifier VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + organization
            + "', NULL, 'abc')");
  }

  @Test
  void appendOnlyPrivilegesPreventHistoricalMutation() throws Exception {
    var id = UUID.randomUUID();
    execute("INSERT INTO baseline_probe.fixture_ledger VALUES ('" + id + "', 'test fact')");
    assertSqlState(
        "42501",
        "UPDATE baseline_probe.fixture_ledger SET note = 'rewritten' WHERE id = '" + id + "'");
    assertSqlState("42501", "DELETE FROM baseline_probe.fixture_ledger WHERE id = '" + id + "'");
  }

  @Test
  void optimisticVersionRejectsStaleWriter() throws Exception {
    var organization = UUID.randomUUID();
    var parent = UUID.randomUUID();
    var id = UUID.randomUUID();
    insertParent(organization, parent);
    execute(recordInsert(id, organization, parent, 0));
    try (var first = sessions.openSession();
        var second = sessions.openSession()) {
      var firstTransaction = first.beginTransaction();
      var secondTransaction = second.beginTransaction();
      var firstRecord = first.find(DatabaseRecord.class, id);
      var secondRecord = second.find(DatabaseRecord.class, id);
      firstRecord.changeMeasuredValue(1);
      firstTransaction.commit();
      secondRecord.changeMeasuredValue(2);
      assertThatThrownBy(secondTransaction::commit)
          .isInstanceOf(jakarta.persistence.OptimisticLockException.class);
    }
  }

  @Test
  void parentLockSerializesWritersAndConstraintFailureRequiresRollback() throws Exception {
    var organization = UUID.randomUUID();
    var parent = UUID.randomUUID();
    insertParent(organization, parent);
    try (var first = connection();
        var second = connection()) {
      assertThat(first.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      try (var one = first.createStatement();
          var two = second.createStatement()) {
        one.executeQuery(
                "SELECT id FROM baseline_probe.fixture_parent WHERE id = '"
                    + parent
                    + "' FOR UPDATE")
            .close();
        two.execute("SET LOCAL lock_timeout = '100ms'");
        assertThatThrownBy(
                () ->
                    two.executeQuery(
                        "SELECT id FROM baseline_probe.fixture_parent WHERE id = '"
                            + parent
                            + "' FOR UPDATE"))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("55P03");
        assertThatThrownBy(() -> two.executeQuery("SELECT 1"))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("25P02");
        second.rollback();
        first.commit();
        two.executeQuery(
                "SELECT id FROM baseline_probe.fixture_parent WHERE id = '"
                    + parent
                    + "' FOR UPDATE")
            .close();
        second.commit();
      }
    }
  }

  @Test
  void concurrentIdempotentClaimCommitsOnlyOneEffect() throws Exception {
    var key = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> claim(key, start));
      var second = executor.submit(() -> claim(key, start));
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    try (var connection = connection();
        var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT result FROM baseline_probe.fixture_command WHERE command_key = '"
                    + key
                    + "'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt(1)).isEqualTo(1);
      assertThat(result.next()).isFalse();
    }
  }

  private int claim(UUID key, CountDownLatch start) throws Exception {
    start.await();
    try (var connection = connection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      int claimed =
          statement.executeUpdate(
              "INSERT INTO baseline_probe.fixture_command VALUES ('"
                  + key
                  + "', 'same-hash', 1) ON CONFLICT DO NOTHING");
      try (var result =
          statement.executeQuery(
              "SELECT request_hash FROM baseline_probe.fixture_command WHERE command_key = '"
                  + key
                  + "'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("same-hash");
      }
      connection.commit();
      return claimed;
    }
  }

  private static Connection connection() throws SQLException {
    return TestDatabase.runtimeConnection();
  }

  private static void execute(String sql) throws SQLException {
    try (var connection = connection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void assertSqlState(String state, String sql) {
    assertThatThrownBy(() -> execute(sql))
        .isInstanceOf(SQLException.class)
        .extracting("SQLState")
        .isEqualTo(state);
  }

  private static void insertParent(UUID organization, UUID id) throws SQLException {
    execute(
        "INSERT INTO baseline_probe.fixture_parent VALUES ('" + id + "', '" + organization + "')");
  }

  private static String reservationInsert(UUID id, UUID organization, UUID resource) {
    return "INSERT INTO baseline_probe.fixture_reservation VALUES ('"
        + id
        + "', '"
        + organization
        + "', '"
        + resource
        + "', true)";
  }

  private static String recordInsert(UUID id, UUID organization, UUID parent, int value) {
    return "INSERT INTO baseline_probe.fixture_record VALUES ('"
        + id
        + "', '"
        + organization
        + "', '"
        + parent
        + "', "
        + value
        + ", '2026-09-08T12:34:56.123456Z', '2026-09-08', '2026-09-08T09:34:56', 'America/Sao_Paulo', 0)";
  }
}
