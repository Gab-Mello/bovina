package com.bovina;

import static org.assertj.core.api.Assertions.*;

import com.bovina.identity.application.*;
import com.bovina.opu.application.*;
import com.bovina.platform.application.*;
import com.bovina.support.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpuIT {
  private static final TrustedTokens TOKENS = new TrustedTokens();
  private static final StableIds IDS = new StableIds();
  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired TenantAccess access;
  @Autowired CollectionAllocationBoundary allocation;
  @Autowired PlatformTransactionManager transactions;
  @Autowired RecordCollections recorder;
  @Autowired jakarta.persistence.EntityManagerFactory entityManagers;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    TestDatabase.properties(r);
    r.add("bovina.security.issuer", () -> TOKENS.issuer().toString());
    r.add("bovina.security.jwk-set-uri", () -> TOKENS.jwks().toString());
    r.add("bovina.bootstrap.enabled", () -> true);
    r.add("bovina.bootstrap.issuer", () -> TOKENS.issuer().toString());
    r.add("bovina.bootstrap.subject", () -> "opu-bootstrap");
    r.add("bovina.opu.intake.transport-enabled", () -> true);
    r.add("bovina.opu.intake.external-receipt-enabled", () -> true);
    r.add("spring.jpa.properties.hibernate.generate_statistics", () -> true);
  }

  @AfterAll
  static void closeKeys() {
    TOKENS.close();
  }

  @Test
  void localOpuCompletesWithoutSireTransportOrReceiptAndFreezesIdentity() throws Exception {
    var f = fixture();
    var donor = animal(f.tenant());
    var collection = IDS.next();
    var batch = batch(row(collection, donor, 10, 8));
    var key = UUID.fromString(batch.get("batchId").toString());
    var recorded = post(f.tenant(), path(f) + "/collections:bulk", key, batch);
    ok(recorded, 200);
    var before = get(f.tenant(), "/oocyte-collections/" + collection);
    ok(before, 200);
    assertThat(before.body()).contains("MANUAL").doesNotContain("sire", "semen", "mating", "Fiv");
    var observed = get(f.tenant(), "/animals?role=DONOR");
    ok(observed, 200);
    assertThat(observed.body()).contains(donor.toString());
    var completionKey = IDS.next();
    var complete =
        post(f.tenant(), path(f) + ":complete", completionKey, Map.of("expectedVersion", 1));
    ok(complete, 200);
    assertThat(
            json.readTree(
                post(f.tenant(), path(f) + ":complete", completionKey, Map.of("expectedVersion", 1))
                    .body()))
        .isEqualTo(json.readTree(complete.body()));
    var snapshot = get(f.tenant(), "/oocyte-collections/" + collection + "/donor-snapshot");
    ok(snapshot, 200);
    jdbc.update("UPDATE animal SET name='Changed later' WHERE id=?", donor);
    assertThat(get(f.tenant(), "/oocyte-collections/" + collection + "/donor-snapshot").body())
        .isEqualTo(snapshot.body());
    assertThat(get(f.tenant(), path(f) + "/summary").body())
        .contains("Original farm", "\"totalRecovered\":10", "\"viable\":8");
    ok(post(f.tenant(), path(f) + "/collections:bulk", key, batch), 200);
    ok(
        post(
            f.tenant(),
            path(f) + "/collections:bulk",
            batch(row(IDS.next(), animal(f.tenant()), 2, 1))),
        409);
    ok(
        post(f.tenant(), "/oocyte-collections/" + collection + ":correct", correction(1, 8, 7)),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='RECORD'",
                Integer.class,
                collection))
        .isEqualTo(1);
    var c = context(f.tenant());
    var capacity =
        new TransactionTemplate(transactions).execute(s -> allocation.lockCompleted(c, collection));
    assertThat(capacity.availableAfter(0)).isEqualTo(8);
    assertThat(capacity.donorId()).isEqualTo(donor);
  }

  @Test
  void dryRunExplainsEachInvalidItemAndBulkRollsBackAllRows() throws Exception {
    var f = fixture();
    var good = IDS.next();
    var input =
        batch(row(good, animal(f.tenant()), 4, 3), row(IDS.next(), animal(f.tenant()), 1, 2));
    var preview = post(f.tenant(), path(f) + "/collections:dry-run", input);
    ok(preview, 200);
    assertThat(preview.body()).contains("VALID", "INVALID_OOCYTE_COUNTS", "REJECTED");
    ok(post(f.tenant(), path(f) + "/collections:bulk", input), 422);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE opu_session_id=?",
                Integer.class,
                f.session()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=?", Integer.class, good))
        .isZero();
  }

  @Test
  void collectionCorrectionIsVersionedAuditedAndKeepsProvenance() throws Exception {
    var f = fixture();
    var id = IDS.next();
    ok(
        post(f.tenant(), path(f) + "/collections:bulk", batch(row(id, animal(f.tenant()), 7, 5))),
        200);
    var before =
        json.readTree(get(f.tenant(), "/oocyte-collections/" + id).body()).path("provenance");
    ok(post(f.tenant(), "/oocyte-collections/" + id + ":correct", correction(0, 6, 4)), 200);
    ok(post(f.tenant(), "/oocyte-collections/" + id + ":correct", correction(0, 6, 4)), 409);
    var after = json.readTree(get(f.tenant(), "/oocyte-collections/" + id).body());
    assertThat(after.path("provenance")).isEqualTo(before);
    assertThat(after.path("registration").path("counts").path("viable").asInt()).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='CORRECT' AND reason IS NOT NULL AND previous_state IS NOT NULL",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void retriesAndConcurrentBulkNeverDuplicateCollectionsOrAudit() throws Exception {
    var f = fixture();
    var id = IDS.next();
    var input = batch(row(id, animal(f.tenant()), 6, 4));
    var barrier = new CyclicBarrier(3);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 3; i++)
        pending.add(
            executor.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  return post(f.tenant(), path(f) + "/collections:bulk", input);
                }));
      JsonNode first = null;
      for (var future : pending) {
        var result = future.get(20, TimeUnit.SECONDS);
        ok(result, 200);
        if (first == null) first = json.readTree(result.body());
        else assertThat(json.readTree(result.body())).isEqualTo(first);
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='RECORD'",
                Integer.class,
                id))
        .isEqualTo(1);
    var changed = new HashMap<String, Object>(input);
    changed.put("items", List.of(row(id, animal(f.tenant()), 5, 4)));
    ok(post(f.tenant(), path(f) + "/collections:bulk", changed), 409);
  }

  @Test
  void databaseChecksAndCompoundReferencesProtectCountsAndTenant() throws Exception {
    var f = fixture();
    var foreign = fixture();
    var id = IDS.next();
    var donor = animal(f.tenant());
    ok(post(f.tenant(), path(f) + "/collections:bulk", batch(row(id, donor, 5, 3))), 200);
    ok(get(foreign.tenant(), "/oocyte-collections/" + id), 404);
    ok(get(foreign.tenant(), path(f)), 404);
    var wrong = batch(row(IDS.next(), animal(foreign.tenant()), 2, 1));
    var preview = post(f.tenant(), path(f) + "/collections:dry-run", wrong);
    ok(preview, 200);
    assertThat(preview.body()).contains("DONOR_NOT_FOUND");
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var set :
          List.of("viable=6", "total_recovered=-1", "viable=-1", "follicles_aspirated=-1"))
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "UPDATE oocyte_collection SET " + set + " WHERE id='" + id + "'"))
            .isInstanceOf(java.sql.SQLException.class)
            .extracting("SQLState")
            .isEqualTo("23514");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE oocyte_collection SET opu_session_id='"
                          + foreign.session()
                          + "' WHERE id='"
                          + id
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23503");
      assertThatThrownBy(
              () -> statement.executeUpdate("DELETE FROM oocyte_collection WHERE id='" + id + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
    ok(post(f.tenant(), path(f) + "/collections:bulk", batch(row(IDS.next(), donor, 2, 1))), 422);
  }

  @Test
  void importAndApiProvenanceRequireEvidenceAndReplayPreservesRecordingTime() throws Exception {
    var f = fixture();
    var document = IDS.next();
    ok(
        post(
            f.tenant(),
            "/document-references",
            Map.of(
                "id",
                document,
                "type",
                "DECLARED_OPU_SOURCE",
                "reference",
                "source/ref",
                "revision",
                "1")),
        201);
    for (var origin : List.of("IMPORT", "API")) {
      var id = IDS.next();
      var input = new HashMap<String, Object>(batch(row(id, animal(f.tenant()), 2, 1)));
      var source = new HashMap<String, Object>();
      source.put("origin", origin);
      source.put("sourceDocumentId", document);
      if (origin.equals("API")) source.put("apiClientId", IDS.next());
      input.put("source", source);
      var first = post(f.tenant(), path(f) + "/collections:bulk", input);
      ok(first, 200);
      var recorded = get(f.tenant(), "/oocyte-collections/" + id);
      assertThat(recorded.body()).contains(origin, document.toString());
      ok(post(f.tenant(), path(f) + "/collections:bulk", input), 200);
      assertThat(get(f.tenant(), "/oocyte-collections/" + id).body()).isEqualTo(recorded.body());
    }
    var invalid = new HashMap<String, Object>(batch(row(IDS.next(), animal(f.tenant()), 1, 1)));
    invalid.put("source", Map.of("origin", "IMPORT"));
    ok(post(f.tenant(), path(f) + "/collections:bulk", invalid), 400);
  }

  @Test
  void runtimeConstraintFailureRollsBackReceiptAndAllowsCorrectedRetry() throws Exception {
    var f = fixture();
    var other = fixture();
    var collision = IDS.next();
    ok(
        post(
            other.tenant(),
            path(other) + "/collections:bulk",
            batch(row(collision, animal(other.tenant()), 1, 1))),
        200);
    var valid = IDS.next();
    var input =
        batch(row(valid, animal(f.tenant()), 3, 2), row(collision, animal(f.tenant()), 3, 2));
    ok(post(f.tenant(), path(f) + "/collections:bulk", input), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE id=?", Integer.class, valid))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotent_command WHERE organization_id=? AND idempotency_key=?",
                Integer.class,
                f.tenant(),
                UUID.fromString(input.get("batchId").toString())))
        .isZero();
    var fixed = new HashMap<String, Object>(input);
    fixed.put("items", List.of(row(valid, animal(f.tenant()), 3, 2)));
    ok(post(f.tenant(), path(f) + "/collections:bulk", fixed), 200);
  }

  @Test
  void completedCollectionLockSerializesConsumersUntilTransactionCommit() throws Exception {
    var f = fixture();
    var id = IDS.next();
    ok(
        post(f.tenant(), path(f) + "/collections:bulk", batch(row(id, animal(f.tenant()), 5, 5))),
        200);
    ok(post(f.tenant(), path(f) + ":complete", Map.of("expectedVersion", 1)), 200);
    var c = context(f.tenant());
    assertThatThrownBy(() -> allocation.lockCompleted(c, id))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    // Test-only consumption facts stand in for Phase 4's Mating rows, not a production ledger.
    try (var connection =
            java.sql.DriverManager.getConnection(
                TestDatabase.POSTGRES.getJdbcUrl(),
                "bovina_migration",
                TestDatabase.MIGRATION_PASSWORD);
        var s = connection.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS allocation_boundary_probe (collection_id uuid NOT NULL, quantity integer NOT NULL)");
      s.execute("GRANT SELECT,INSERT ON allocation_boundary_probe TO bovina_runtime");
    }
    var ready = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            var locked = allocation.lockCompleted(c, id);
                            locked.requireAllocation(0, 4);
                            ready.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Release timeout");
                            } catch (InterruptedException e) {
                              throw new AssertionError(e);
                            }
                            jdbc.update("INSERT INTO allocation_boundary_probe VALUES (?,4)", id);
                            return true;
                          }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      var second =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            var locked = allocation.lockCompleted(c, id);
                            var consumed =
                                jdbc.queryForObject(
                                    "SELECT coalesce(sum(quantity),0) FROM allocation_boundary_probe WHERE collection_id=?",
                                    Long.class,
                                    id);
                            locked.requireAllocation(consumed, 4);
                            jdbc.update("INSERT INTO allocation_boundary_probe VALUES (?,4)", id);
                            return true;
                          }));
      try {
        awaitCollectionLock();
        assertThat(second.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ApplicationFailure.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT sum(quantity) FROM allocation_boundary_probe WHERE collection_id=?",
                  Long.class,
                  id))
          .isEqualTo(4);
    }
  }

  private void awaitCollectionLock() throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock' AND query ILIKE '%oocyte_collection%'",
              Integer.class)
          > 0) return;
      Thread.sleep(10);
    }
    fail("Second allocation preparation did not reach the PostgreSQL lock");
  }

  @Test
  void transportRecordsObservedDivergenceWithoutChangingCollectionCounts() throws Exception {
    var f = fixture();
    var id = IDS.next();
    ok(
        post(f.tenant(), path(f) + "/collections:bulk", batch(row(id, animal(f.tenant()), 8, 6))),
        200);
    var transport = IDS.next();
    var input =
        Map.of(
            "id",
            transport,
            "sourceSessionId",
            f.session(),
            "destinationEstablishmentId",
            f.establishment(),
            "dispatchedAt",
            Instant.EPOCH,
            "receivedAt",
            Instant.EPOCH.plusSeconds(36 * 3600),
            "items",
            List.of(Map.of("collectionId", id, "quantityAtDispatch", 8, "quantityAtReceipt", 5)));
    var key = IDS.next();
    var result = post(f.tenant(), "/oocyte-transports", key, input);
    ok(result, 201);
    assertThat(json.readTree(post(f.tenant(), "/oocyte-transports", key, input).body()))
        .isEqualTo(json.readTree(result.body()));
    var read = get(f.tenant(), "/oocyte-transports/" + transport);
    ok(read, 200);
    assertThat(read.body())
        .contains("quantityAtDispatch", "quantityAtReceipt")
        .doesNotContain("WITHIN_PROTOCOL", "ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT viable FROM oocyte_collection WHERE id=?", Integer.class, id))
        .isEqualTo(6);
    var other = fixture();
    ok(get(other.tenant(), "/oocyte-transports/" + transport), 404);
    var mismatch = new HashMap<String, Object>(input);
    mismatch.put("id", IDS.next());
    mismatch.put("sourceSessionId", other.session());
    ok(post(other.tenant(), "/oocyte-transports", mismatch), 404);
    mismatch.put("destinationEstablishmentId", other.establishment());
    ok(post(other.tenant(), "/oocyte-transports", mismatch), 409);
    try (var connection = TestDatabase.runtimeConnection();
        var s = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "UPDATE oocyte_transport SET notes='rewrite' WHERE id='" + transport + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  @Test
  void externalReceiptDoesNotFabricateLocalSessionDonorOrApproval() throws Exception {
    var f = fixture();
    var id = IDS.next();
    var key = IDS.next();
    var input =
        Map.of(
            "id",
            id,
            "receivedAt",
            Instant.EPOCH,
            "sourceReference",
            "External lab declared shipment #42",
            "totalReceived",
            12);
    var result = post(f.tenant(), "/external-oocyte-receipts", key, input);
    ok(result, 201);
    assertThat(result.body())
        .contains("RECEIVED", "MANUAL")
        .doesNotContain("ACCEPTED", "donorId", "sourceOpuSessionId");
    assertThat(json.readTree(post(f.tenant(), "/external-oocyte-receipts", key, input).body()))
        .isEqualTo(json.readTree(result.body()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM opu_session WHERE organization_id=?",
                Integer.class,
                f.tenant()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE organization_id=?",
                Integer.class,
                f.tenant()))
        .isZero();
    var invalid = new HashMap<String, Object>(input);
    invalid.remove("totalReceived");
    ok(post(f.tenant(), "/external-oocyte-receipts", invalid), 400);
    var foreign = fixture();
    ok(get(foreign.tenant(), "/external-oocyte-receipts/" + id), 404);
  }

  @Test
  void readOnlyMembershipCannotRecordOpuEvenWithoutHttp() throws Exception {
    var f = fixture();
    var subject = "opu-reader-" + IDS.next();
    ok(
        post(
            f.tenant(),
            "/memberships",
            Map.of("id", IDS.next(), "subject", subject, "role", "READ_ONLY")),
        201);
    var c =
        access.resolve(
            new AuthenticatedIdentity(TOKENS.issuer().toString(), subject), f.tenant(), IDS.next());
    var input =
        json.readValue(
            json.writeValueAsString(batch(row(IDS.next(), animal(f.tenant()), 1, 1))),
            CollectionBatch.class);
    assertThatThrownBy(() -> recorder.record(c, input.batchId(), f.session(), input))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1" + path(f) + ":complete"))
            .header(
                "Authorization",
                "Bearer "
                    + TOKENS.token(
                        subject,
                        TOKENS.issuer().toString(),
                        "bovina-test",
                        Instant.now().plusSeconds(600)))
            .header("X-Organization-ID", f.tenant().toString())
            .header("Idempotency-Key", IDS.next().toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":1}"))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      ok(client.send(request, HttpResponse.BodyHandlers.ofString()), 403);
    }
  }

  @Test
  void completionUsesBoundedQueriesAndDoesNotLoadACollectionGraph() throws Exception {
    var f = fixture();
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 20; i++) rows.add(row(IDS.next(), animal(f.tenant()), 2, 1));
    var input = Map.of("batchId", IDS.next(), "expectedSessionVersion", 1, "items", rows);
    ok(post(f.tenant(), path(f) + "/collections:bulk", input), 200);
    var statistics = entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    statistics.clear();
    ok(post(f.tenant(), path(f) + ":complete", Map.of("expectedVersion", 1)), 200);
    assertThat(statistics.getPrepareStatementCount()).isLessThan(20);
    assertThat(statistics.getCollectionFetchCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_donor_snapshot WHERE organization_id=?",
                Integer.class,
                f.tenant()))
        .isEqualTo(20);
    var page = get(f.tenant(), path(f) + "/collections?size=3&page=1");
    ok(page, 200);
    assertThat(json.readTree(page.body()).path("items").size()).isEqualTo(3);
  }

  @Test
  void completionAndBulkShareTheSameTransactionFence() throws Exception {
    var f = fixture();
    var id = IDS.next();
    var input = batch(row(id, animal(f.tenant()), 4, 3));
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var bulk =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return post(f.tenant(), path(f) + "/collections:bulk", input);
              });
      var complete =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return post(f.tenant(), path(f) + ":complete", Map.of("expectedVersion", 1));
              });
      ok(complete.get(20, TimeUnit.SECONDS), 200);
      var result = bulk.get(20, TimeUnit.SECONDS);
      assertThat(result.statusCode()).isIn(200, 409);
      var statuses =
          jdbc.queryForList(
              "SELECT status FROM oocyte_collection WHERE opu_session_id=?",
              String.class,
              f.session());
      if (result.statusCode() == 200) assertThat(statuses).containsExactly("COMPLETED");
      else assertThat(statuses).isEmpty();
    }
  }

  private Map<String, Object> correction(long version, int total, int viable) {
    return Map.of(
        "expectedVersion",
        version,
        "counts",
        Map.of("totalRecovered", total, "viable", viable),
        "reason",
        "Observed count correction");
  }

  @SafeVarargs
  private Map<String, Object> batch(Map<String, Object>... items) {
    return Map.of("batchId", IDS.next(), "expectedSessionVersion", 1, "items", List.of(items));
  }

  private Map<String, Object> row(UUID id, UUID donor, int total, int viable) {
    return Map.of(
        "itemId",
        IDS.next(),
        "id",
        id,
        "donorId",
        donor,
        "collectedAt",
        Instant.EPOCH,
        "totalRecovered",
        total,
        "viable",
        viable);
  }

  private String path(Fixture f) {
    return "/opu-sessions/" + f.session();
  }

  private ExecutionContext context(UUID tenant) {
    return access.resolve(
        new AuthenticatedIdentity(TOKENS.issuer().toString(), "opu-bootstrap"), tenant, IDS.next());
  }

  private UUID animal(UUID tenant) throws Exception {
    var id = IDS.next();
    ok(post(tenant, "/animals", Map.of("id", id, "sex", "FEMALE", "name", "Original donor")), 201);
    return id;
  }

  private Fixture fixture() throws Exception {
    var tenant = IDS.next();
    ok(
        post(
            null,
            "/bootstrap/organizations",
            Map.of(
                "id",
                tenant,
                "legalName",
                "OPU tenant",
                "taxId",
                "test-" + tenant.toString().substring(0, 8),
                "timezone",
                "America/Sao_Paulo")),
        201);
    var establishment = IDS.next();
    var property = IDS.next();
    var professional = IDS.next();
    var session = IDS.next();
    var address =
        Map.of("addressLine", "Road", "municipality", "City", "state", "SP", "country", "BR");
    ok(
        post(
            tenant,
            "/establishments",
            Map.of(
                "id",
                establishment,
                "legalDisplayName",
                "Lab",
                "operatingMode",
                "COMMERCIAL",
                "address",
                address)),
        201);
    ok(
        post(
            tenant,
            "/farm-properties",
            Map.of("id", property, "name", "Original farm", "address", address)),
        201);
    ok(
        post(
            tenant,
            "/professionals",
            Map.of("id", professional, "name", "Operator", "professionalType", "VETERINARIAN")),
        201);
    var input =
        Map.of(
            "id",
            session,
            "establishmentId",
            establishment,
            "farmPropertyId",
            property,
            "leadProfessionalId",
            professional,
            "performedAt",
            Instant.EPOCH,
            "timezone",
            "America/Sao_Paulo");
    ok(post(tenant, "/opu-sessions", input), 201);
    ok(post(tenant, "/opu-sessions/" + session + ":start", Map.of("expectedVersion", 0)), 200);
    return new Fixture(tenant, session, establishment, property, professional);
  }

  private record Fixture(
      UUID tenant, UUID session, UUID establishment, UUID property, UUID professional) {}

  private void ok(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
  }

  private HttpResponse<String> post(UUID tenant, String path, Object body) throws Exception {
    UUID key =
        body instanceof Map<?, ?> m && m.containsKey("batchId")
            ? (UUID) m.get("batchId")
            : IDS.next();
    return post(tenant, path, key, body);
  }

  private HttpResponse<String> post(UUID tenant, String path, UUID key, Object body)
      throws Exception {
    return request("POST", tenant, path, key, body);
  }

  private HttpResponse<String> get(UUID tenant, String path) throws Exception {
    return request("GET", tenant, path, null, null);
  }

  private HttpResponse<String> request(
      String method, UUID tenant, String path, UUID key, Object body) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(20))
            .header(
                "Authorization",
                "Bearer "
                    + TOKENS.token(
                        "opu-bootstrap",
                        TOKENS.issuer().toString(),
                        "bovina-test",
                        Instant.now().plusSeconds(600)))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (tenant != null) b.header("X-Organization-ID", tenant.toString());
    if (key != null) b.header("Idempotency-Key", key.toString());
    if (body != null) b.header("Content-Type", "application/json");
    try (var client = HttpClient.newHttpClient()) {
      return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
