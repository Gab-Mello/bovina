package com.bovina.opu;

import static com.bovina.support.fixture.OpuFixtures.*;
import static com.bovina.support.integration.PostgresTestAssertions.awaitLockWait;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.application.CollectionAllocationBoundary;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.support.TestDatabase;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OocyteAllocationConcurrencyIT extends OpuIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private CollectionAllocationBoundary allocation;
  @Autowired private PlatformTransactionManager transactions;

  @Test
  void collectionLockSerializesConsumersAndPreventsOverAllocation() throws Exception {
    var opu = startedSession(api, tenant("Allocation Boundary Lab"));
    var collection = id();
    assertStatus(
        api.post(
            opu.tenant().id(),
            opu.path() + "/collections:bulk",
            collectionBatch(collection(collection, donor(api, opu.tenant(), "Donor A"), 5, 5))),
        200);
    assertStatus(
        api.post(opu.tenant().id(), opu.path() + ":complete", Map.of("expectedVersion", 1)), 200);
    var context =
        access.resolve(
            new AuthenticatedIdentity(issuer(), opu.tenant().subject()), opu.tenant().id(), id());
    assertThatThrownBy(() -> allocation.lockCompleted(context, collection))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    createAllocationProbe();

    var firstHasLock = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            var locked = allocation.lockCompleted(context, collection);
                            locked.requireAllocation(0, 4);
                            firstHasLock.countDown();
                            try {
                              if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out waiting to release collection");
                              }
                            } catch (InterruptedException exception) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(exception);
                            }
                            jdbc.update(
                                "INSERT INTO allocation_boundary_probe VALUES (?,4)", collection);
                            return true;
                          }));
      assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
      var competing =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            var locked = allocation.lockCompleted(context, collection);
                            var consumed =
                                jdbc.queryForObject(
                                    "SELECT coalesce(sum(quantity),0) FROM allocation_boundary_probe WHERE collection_id=?",
                                    Long.class,
                                    collection);
                            locked.requireAllocation(consumed, 4);
                            jdbc.update(
                                "INSERT INTO allocation_boundary_probe VALUES (?,4)", collection);
                            return true;
                          }));
      try {
        awaitLockWait("oocyte_collection");
        assertThat(competing.isDone()).isFalse();
      } finally {
        releaseFirst.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> competing.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ApplicationFailure.class);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(quantity) FROM allocation_boundary_probe WHERE collection_id=?",
                Long.class,
                collection))
        .isEqualTo(4);
  }

  @Test
  void completionAndBulkCollectionWriteShareTheSessionTransactionFence() throws Exception {
    var opu = startedSession(api, tenant("Session Fence Lab"));
    var collection = id();
    var command =
        collectionBatch(collection(collection, donor(api, opu.tenant(), "Donor A"), 4, 3));
    var start = new CyclicBarrier(2);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var bulk =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command);
              });
      var completion =
          executor.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return api.post(
                    opu.tenant().id(), opu.path() + ":complete", Map.of("expectedVersion", 1));
              });

      assertStatus(completion.get(20, TimeUnit.SECONDS), 200);
      var bulkResponse = bulk.get(20, TimeUnit.SECONDS);
      assertThat(bulkResponse.statusCode()).isIn(200, 409);
      var statuses =
          jdbc.queryForList(
              "SELECT status FROM oocyte_collection WHERE opu_session_id=?",
              String.class,
              opu.session());
      if (bulkResponse.statusCode() == 200) {
        assertThat(statuses).containsExactly("COMPLETED");
      } else {
        assertThat(statuses).isEmpty();
      }
    }
  }

  private void createAllocationProbe() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                TestDatabase.POSTGRES.getJdbcUrl(),
                "bovina_migration",
                TestDatabase.MIGRATION_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS allocation_boundary_probe "
              + "(collection_id uuid NOT NULL, quantity integer NOT NULL)");
      statement.execute("GRANT SELECT,INSERT ON allocation_boundary_probe TO bovina_runtime");
    }
  }
}
