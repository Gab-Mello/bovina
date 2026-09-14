package com.bovina;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.StableIds;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase4IT {
  private static final TrustedTokens TOKENS = new TrustedTokens();
  private static final StableIds IDS = new StableIds();
  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
    registry.add("bovina.security.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.security.jwk-set-uri", () -> TOKENS.jwks().toString());
    registry.add("bovina.bootstrap.enabled", () -> true);
    registry.add("bovina.bootstrap.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.bootstrap.subject", () -> "phase4-bootstrap");
  }

  @AfterAll
  static void closeKeys() {
    TOKENS.close();
  }

  @Test
  void semenAndMultiMatingCreateBidirectionalTenantSafeLineage() throws Exception {
    var fixture = fixture(5);
    var first = IDS.next();
    var second = IDS.next();
    var batchId = IDS.next();
    var input = matingBatch(batchId, fixture, item(first, fixture, 2), item(second, fixture, 3));
    var created = post(fixture.tenant(), "/matings:bulk", batchId, input);
    ok(created, 200);
    assertThat(json.readTree(post(fixture.tenant(), "/matings:bulk", batchId, input).body()))
        .isEqualTo(json.readTree(created.body()));
    ok(
        post(
            fixture.tenant(),
            "/matings:bulk",
            batchId,
            matingBatch(batchId, fixture, item(IDS.next(), fixture, 1))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(allocated_oocytes) FROM mating WHERE oocyte_collection_id=?",
                Integer.class,
                fixture.collection()))
        .isEqualTo(5);
    assertThat(get(fixture.tenant(), "/matings/" + first).body())
        .contains(
            fixture.collection().toString(),
            fixture.semenBatch().toString(),
            fixture.donor().toString(),
            fixture.sire().toString());
    assertThat(get(fixture.tenant(), "/matings?collectionId=" + fixture.collection()).body())
        .contains(first.toString(), second.toString());
    assertThat(get(fixture.tenant(), "/matings?semenBatchId=" + fixture.semenBatch()).body())
        .contains(first.toString(), second.toString());
    ok(
        post(
            fixture.tenant(),
            "/matings/" + first + "/corrections",
            Map.of("reason", "Supplier document correction requested")),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM record_correction WHERE subject_id=?", String.class, first))
        .isEqualTo("REQUESTED");
    var foreign = fixture(1);
    ok(get(foreign.tenant(), "/matings/" + first), 404);
    ok(get(foreign.tenant(), "/semen-batches/" + fixture.semenBatch()), 404);
  }

  @Test
  void concurrentMatingsCannotOverAllocateCollectionBalance() throws Exception {
    var fixture = fixture(3);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 2; i++) {
        var mating = IDS.next();
        var batch = IDS.next();
        var input = matingBatch(batch, fixture, item(mating, fixture, 2));
        pending.add(
            executor.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  return post(fixture.tenant(), "/matings:bulk", batch, input);
                }));
      }
      var statuses = new ArrayList<Integer>();
      for (var future : pending) statuses.add(future.get(30, TimeUnit.SECONDS).statusCode());
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mating WHERE oocyte_collection_id=?",
                Integer.class,
                fixture.collection()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(allocated_oocytes) FROM mating WHERE oocyte_collection_id=?",
                Integer.class,
                fixture.collection()))
        .isEqualTo(2);
  }

  @Test
  void embryoStateAssessmentHistoryAndReconciliationRemainOrthogonal() throws Exception {
    var fixture = fixture(3);
    var mating = IDS.next();
    var matingBatch = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/matings:bulk",
            matingBatch,
            matingBatch(matingBatch, fixture, item(mating, fixture, 3))),
        200);
    var embryo = IDS.next();
    var secondEmbryo = IDS.next();
    var identifyBatch = IDS.next();
    var identify =
        Map.of(
            "batchId",
            identifyBatch,
            "items",
            List.of(
                embryoItem(embryo, mating, "E-001"), embryoItem(secondEmbryo, mating, "E-002")));
    ok(post(fixture.tenant(), "/embryos:bulk", identifyBatch, identify), 200);
    ok(post(fixture.tenant(), "/embryos:bulk", identifyBatch, identify), 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo WHERE mating_id=?", Integer.class, mating))
        .isEqualTo(2);
    var scheme = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/assessment-schemes",
            Map.of("id", scheme, "code", "LAB_SCHEME", "name", "Lab scheme")),
        201);
    var version = IDS.next();
    var stage = IDS.next();
    var grade = IDS.next();
    var published =
        post(
            fixture.tenant(),
            "/assessment-schemes/" + scheme + "/versions",
            Map.of(
                "id",
                version,
                "versionLabel",
                "2026.1",
                "codes",
                List.of(
                    Map.of(
                        "id",
                        stage,
                        "dimension",
                        "DEVELOPMENT_STAGE",
                        "code",
                        "MORULA",
                        "displayName",
                        "Morula",
                        "sortOrder",
                        1),
                    Map.of(
                        "id",
                        grade,
                        "dimension",
                        "QUALITY_GRADE",
                        "code",
                        "A",
                        "displayName",
                        "Grade A",
                        "sortOrder",
                        1))));
    ok(published, 201);
    assertThat(published.headers().firstValue("Location"))
        .contains("/api/v1/assessment-schemes/versions/" + version);
    var firstEvaluation = IDS.next();
    evaluate(fixture.tenant(), embryo, firstEvaluation, version, stage, grade, null);
    var secondEvaluation = IDS.next();
    evaluate(fixture.tenant(), embryo, secondEvaluation, version, stage, grade, firstEvaluation);
    assertThat(get(fixture.tenant(), "/embryos/" + embryo).body())
        .contains(
            "\"preservation\":\"FRESH\"",
            "\"currentLocationId\":null",
            secondEvaluation.toString());
    assertThat(
            json.readTree(get(fixture.tenant(), "/embryos/" + embryo + "/evaluations").body())
                .size())
        .isEqualTo(2);

    var hold = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/embryos/" + embryo + "/holds",
            Map.of("id", hold, "type", "QUALITY", "reason", "Review pending")),
        200);
    ok(
        post(
            fixture.tenant(),
            "/embryos/" + embryo + ":discard",
            Map.of("expectedVersion", 0, "reason", "Blocked while on hold")),
        409);
    ok(
        post(
            fixture.tenant(),
            "/embryos/" + embryo + "/holds/" + hold + ":release",
            Map.of("reason", "Review completed")),
        200);
    ok(
        post(
            fixture.tenant(),
            "/embryos/" + embryo + ":discard",
            Map.of("expectedVersion", 0, "reason", "Assessment completed")),
        200);

    var disposition = IDS.next();
    var completeKey = IDS.next();
    var completion =
        Map.of(
            "expectedVersion",
            0,
            "producedCount",
            3,
            "dispositions",
            List.of(
                Map.of(
                    "id",
                    disposition,
                    "code",
                    "NOT_INDIVIDUALIZED",
                    "quantity",
                    1,
                    "reason",
                    "Observed aggregate outcome")));
    ok(
        post(
            fixture.tenant(),
            "/matings/" + mating + ":complete-embryology",
            completeKey,
            completion),
        200);
    ok(
        post(
            fixture.tenant(),
            "/matings/" + mating + ":complete-embryology",
            completeKey,
            completion),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT produced_count FROM mating_completion WHERE mating_id=?",
                Integer.class,
                mating))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT evaluation_id FROM embryo_current_assessment WHERE embryo_id=?",
                UUID.class,
                embryo))
        .isEqualTo(secondEvaluation);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE embryo_evaluation SET notes='rewrite' WHERE id='"
                          + firstEvaluation
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE assessment_code SET display_name='rewrite' WHERE id='" + stage + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  private void evaluate(
      UUID tenant, UUID embryo, UUID id, UUID version, UUID stage, UUID grade, UUID supersedes)
      throws Exception {
    var batch = IDS.next();
    var item = new HashMap<String, Object>();
    item.put("itemId", IDS.next());
    item.put("id", id);
    item.put("embryoId", embryo);
    item.put("schemeVersionId", version);
    item.put("developmentStageCodeId", stage);
    item.put("qualityGradeCodeId", grade);
    item.put("evaluatedAt", Instant.EPOCH);
    if (supersedes != null) item.put("supersedesEvaluationId", supersedes);
    ok(
        post(
            tenant,
            "/embryo-evaluations:bulk",
            batch,
            Map.of("batchId", batch, "items", List.of(item))),
        200);
  }

  private Map<String, Object> embryoItem(UUID id, UUID mating, String code) {
    return Map.of(
        "itemId",
        IDS.next(),
        "id",
        id,
        "matingId",
        mating,
        "humanCode",
        code,
        "identifiedAt",
        Instant.EPOCH);
  }

  private Map<String, Object> item(UUID id, Fixture f, int allocation) {
    return Map.of(
        "itemId",
        IDS.next(),
        "id",
        id,
        "collectionId",
        f.collection(),
        "semenBatchId",
        f.semenBatch(),
        "allocatedOocytes",
        allocation,
        "fertilizedAt",
        Instant.EPOCH,
        "method",
        "IVF",
        "responsibleProfessionalId",
        f.professional());
  }

  @SafeVarargs
  private Map<String, Object> matingBatch(UUID batch, Fixture f, Map<String, Object>... items) {
    return Map.of("batchId", batch, "items", List.of(items));
  }

  private Fixture fixture(int viable) throws Exception {
    var tenant = IDS.next();
    ok(
        post(
            null,
            "/bootstrap/organizations",
            Map.of(
                "id",
                tenant,
                "legalName",
                "Phase 4 tenant",
                "taxId",
                "p4-" + tenant.toString().substring(0, 8),
                "timezone",
                "America/Sao_Paulo")),
        201);
    var address =
        Map.of("addressLine", "Road", "municipality", "City", "state", "SP", "country", "BR");
    var establishment = IDS.next();
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
    var property = IDS.next();
    ok(
        post(
            tenant, "/farm-properties", Map.of("id", property, "name", "Farm", "address", address)),
        201);
    var professional = IDS.next();
    ok(
        post(
            tenant,
            "/professionals",
            Map.of("id", professional, "name", "Embryologist", "professionalType", "VETERINARIAN")),
        201);
    var donor = IDS.next();
    ok(post(tenant, "/animals", Map.of("id", donor, "sex", "FEMALE", "name", "Donor")), 201);
    var sire = IDS.next();
    ok(post(tenant, "/animals", Map.of("id", sire, "sex", "MALE", "name", "Sire")), 201);
    var session = IDS.next();
    ok(
        post(
            tenant,
            "/opu-sessions",
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
                "America/Sao_Paulo")),
        201);
    ok(post(tenant, "/opu-sessions/" + session + ":start", Map.of("expectedVersion", 0)), 200);
    var collection = IDS.next();
    var collectionBatch = IDS.next();
    ok(
        post(
            tenant,
            "/opu-sessions/" + session + "/collections:bulk",
            collectionBatch,
            Map.of(
                "batchId",
                collectionBatch,
                "expectedSessionVersion",
                1,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        IDS.next(),
                        "id",
                        collection,
                        "donorId",
                        donor,
                        "collectedAt",
                        Instant.EPOCH,
                        "totalRecovered",
                        viable,
                        "viable",
                        viable)))),
        200);
    ok(post(tenant, "/opu-sessions/" + session + ":complete", Map.of("expectedVersion", 1)), 200);
    var producer = IDS.next();
    ok(
        post(
            tenant,
            "/external-establishments",
            Map.of(
                "id",
                producer,
                "name",
                "Producer",
                "establishmentType",
                "SEMEN_CENTER",
                "country",
                "BR",
                "verificationStatus",
                "DOCUMENTED")),
        201);
    var semen = IDS.next();
    ok(
        post(
            tenant,
            "/semen-batches",
            Map.of(
                "id",
                semen,
                "batchCode",
                "LOT-1",
                "sireId",
                sire,
                "producerEstablishmentId",
                producer,
                "provenanceCode",
                "SUPPLIER_DOCUMENT",
                "verificationStatus",
                "DOCUMENTED")),
        201);
    return new Fixture(tenant, collection, donor, sire, professional, semen);
  }

  private record Fixture(
      UUID tenant, UUID collection, UUID donor, UUID sire, UUID professional, UUID semenBatch) {}

  private void ok(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
  }

  private HttpResponse<String> post(UUID tenant, String path, Object body) throws Exception {
    return post(tenant, path, IDS.next(), body);
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
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(30))
            .header(
                "Authorization",
                "Bearer "
                    + TOKENS.token(
                        "phase4-bootstrap",
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
