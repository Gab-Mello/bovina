package com.bovina.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class TestDatabase {
  public static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
  public static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();
  public static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(System.getProperty("postgres.image", "postgres:18.6-bookworm"))
          .withDatabaseName("bovina_test")
          .withUsername("bootstrap")
          .withPassword(UUID.randomUUID().toString());

  static {
    POSTGRES.start();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE ROLE bovina_migration LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'");
      statement.execute("CREATE ROLE bovina_runtime LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
      statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO bovina_migration");
      statement.execute("GRANT USAGE ON SCHEMA public TO bovina_runtime");
    } catch (SQLException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private TestDatabase() {}

  public static Connection runtimeConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "bovina_runtime", RUNTIME_PASSWORD);
  }

  public static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "bovina_runtime");
    registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "bovina_migration");
    registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    registry.add("bovina.security.issuer", () -> "https://identity.invalid");
    registry.add("bovina.security.jwk-set-uri", () -> "https://identity.invalid/jwks");
    registry.add("bovina.security.audience", () -> "bovina-test");
  }
}
