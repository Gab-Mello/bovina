package com.bovina.support.integration;

import static org.assertj.core.api.Assertions.fail;

import com.bovina.support.TestDatabase;
import java.util.concurrent.TimeUnit;

public final class PostgresTestAssertions {
  private PostgresTestAssertions() {}

  public static void awaitLockWait(String queryFragment) throws Exception {
    awaitLockWait(queryFragment, 1);
  }

  public static void awaitLockWait(String queryFragment, int minimumWaiters) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var connection = TestDatabase.runtimeConnection();
        var statement =
            connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")) {
      statement.setString(1, "%" + queryFragment + "%");
      while (System.nanoTime() < deadline) {
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (rows.getInt(1) >= minimumWaiters) {
            return;
          }
        }
        Thread.yield();
      }
    }
    fail(
        "Expected at least "
            + minimumWaiters
            + " PostgreSQL lock waits for query containing: "
            + queryFragment);
  }
}
