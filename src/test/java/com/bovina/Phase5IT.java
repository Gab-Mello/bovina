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
class Phase5IT {
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
    registry.add("bovina.bootstrap.subject", () -> "phase5-bootstrap");
  }

  @AfterAll
  static void closeKeys() {
    TOKENS.close();
  }

  @Test
  void freshTransferKeepsCanonicalLineageAndHistoricalOutcomes() throws Exception {
    var fixture = fixture(1);
    var firstCycle = openCycle(fixture.tenant(), fixture.recipient(), LocalDate.of(2026, 8, 1));
    var secondCycle = openCycle(fixture.tenant(), fixture.recipient(), LocalDate.of(2026, 9, 1));
    assertThat(get(fixture.tenant(), "/recipient-cycles?recipientId=" + fixture.recipient()).body())
        .contains(firstCycle.toString(), secondCycle.toString());

    var reservation = IDS.next();
    var reserveBatch = IDS.next();
    var reserve =
        reservationBatch(reserveBatch, reservation, fixture.embryos().getFirst(), secondCycle, 0);
    var reserved = post(fixture.tenant(), "/transfers:bulk-reserve", reserveBatch, reserve);
    ok(reserved, 200);
    assertThat(
            json.readTree(
                post(fixture.tenant(), "/transfers:bulk-reserve", reserveBatch, reserve).body()))
        .isEqualTo(json.readTree(reserved.body()));
    ok(
        post(
            fixture.tenant(),
            "/transfers:bulk-reserve",
            reserveBatch,
            reservationBatch(
                reserveBatch, IDS.next(), fixture.embryos().getFirst(), secondCycle, 0)),
        409);

    var transfer = IDS.next();
    var performBatch = IDS.next();
    var performance =
        performanceBatch(
            performBatch,
            transfer,
            reservation,
            1,
            fixture.professional(),
            Instant.parse("2026-09-01T12:00:00Z"));
    ok(post(fixture.tenant(), "/transfers:bulk-perform", performBatch, performance), 200);
    ok(post(fixture.tenant(), "/transfers:bulk-perform", performBatch, performance), 200);
    var lineage = get(fixture.tenant(), "/transfers/" + transfer);
    ok(lineage, 200);
    assertThat(lineage.body())
        .contains(
            fixture.donor().toString(),
            fixture.sire().toString(),
            fixture.semenBatch().toString(),
            fixture.collection().toString(),
            fixture.mating().toString());
    ok(
        post(
            fixture.tenant(),
            "/transfers:bulk-reserve",
            reservationBatch(IDS.next(), IDS.next(), fixture.embryos().getFirst(), firstCycle, 2)),
        409);

    var d30 = IDS.next();
    var d30Batch = IDS.next();
    var d30Intent =
        checkBatch(
            d30Batch,
            checkItem(
                d30,
                transfer,
                Instant.parse("2026-10-01T12:00:00Z"),
                "PREGNANT",
                fixture.professional(),
                null,
                null));
    ok(post(fixture.tenant(), "/pregnancy-checks:bulk", d30Batch, d30Intent), 200);
    ok(post(fixture.tenant(), "/pregnancy-checks:bulk", d30Batch, d30Intent), 200);

    var d60 = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/pregnancy-checks:bulk",
            checkBatch(
                IDS.next(),
                checkItem(
                    d60,
                    transfer,
                    Instant.parse("2026-10-31T12:00:00Z"),
                    "PREGNANCY_LOSS",
                    fixture.professional(),
                    null,
                    null))),
        200);
    var corrected = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/pregnancy-checks:bulk",
            checkBatch(
                IDS.next(),
                checkItem(
                    corrected,
                    transfer,
                    Instant.parse("2026-10-31T12:00:00Z"),
                    "NOT_PREGNANT",
                    fixture.professional(),
                    d60,
                    "Corrected diagnostic interpretation"))),
        200);

    assertThat(get(fixture.tenant(), "/transfers/" + transfer + "/pregnancy-outcome").body())
        .contains(corrected.toString(), "NOT_PREGNANT");
    var history =
        json.readTree(get(fixture.tenant(), "/transfers/" + transfer + "/pregnancy-checks").body());
    assertThat(history.get("items").size()).isEqualTo(3);
    assertThat(history.toString()).contains(d60.toString(), "Corrected diagnostic interpretation");
    assertThat(get(fixture.tenant(), "/pregnancy-follow-ups?cohort=D30&asOf=2026-10-01").body())
        .contains(transfer.toString(), d30.toString(), "RECORDED");
    assertThat(get(fixture.tenant(), "/pregnancy-follow-ups?cohort=D60&asOf=2026-10-31").body())
        .contains(transfer.toString(), corrected.toString(), "RECORDED");
    var invalidationKey = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/pregnancy-checks/" + corrected + ":invalidate",
            invalidationKey,
            Map.of("reason", "Diagnostic evidence withdrawn")),
        200);
    ok(
        post(
            fixture.tenant(),
            "/pregnancy-checks/" + corrected + ":invalidate",
            invalidationKey,
            Map.of("reason", "Diagnostic evidence withdrawn")),
        200);
    assertThat(get(fixture.tenant(), "/transfers/" + transfer + "/pregnancy-outcome").body())
        .contains(d30.toString(), "PREGNANT")
        .doesNotContain(corrected.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pregnancy_check WHERE transfer_id=?",
                Integer.class,
                transfer))
        .isEqualTo(3);
  }

  @Test
  void deterministicLocksPreventDuplicateReservationAndTransfer() throws Exception {
    var fixture = fixture(2);
    var firstCycle = openCycle(fixture.tenant(), fixture.recipient(), LocalDate.of(2026, 9, 1));
    var secondCycle =
        openCycle(fixture.tenant(), fixture.secondRecipient(), LocalDate.of(2026, 9, 1));
    var firstReservations = List.of(IDS.next(), IDS.next());
    var secondReservations = List.of(IDS.next(), IDS.next());
    var firstBatch = IDS.next();
    var secondBatch = IDS.next();
    var firstIntent =
        reservationBatch(
            firstBatch,
            List.of(
                reservationItem(firstReservations.get(0), fixture.embryos().get(0), firstCycle, 0),
                reservationItem(
                    firstReservations.get(1), fixture.embryos().get(1), secondCycle, 0)));
    var secondIntent =
        reservationBatch(
            secondBatch,
            List.of(
                reservationItem(secondReservations.get(0), fixture.embryos().get(1), firstCycle, 0),
                reservationItem(
                    secondReservations.get(1), fixture.embryos().get(0), secondCycle, 0)));
    var responses =
        concurrent(
            () -> post(fixture.tenant(), "/transfers:bulk-reserve", firstBatch, firstIntent),
            () -> post(fixture.tenant(), "/transfers:bulk-reserve", secondBatch, secondIntent));
    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    var winningReservations =
        responses.get(0).statusCode() == 200 ? firstReservations : secondReservations;
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer_reservation WHERE organization_id=? AND status='ACTIVE'",
                Integer.class,
                fixture.tenant()))
        .isEqualTo(2);
    var losingReservations =
        responses.get(0).statusCode() == 200 ? secondReservations : firstReservations;
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer_reservation WHERE id IN (?,?)",
                Integer.class,
                losingReservations.get(0),
                losingReservations.get(1)))
        .isZero();

    var reservation = winningReservations.getFirst();
    var ref = reservationRef(reservation);
    var performOne = IDS.next();
    var performTwo = IDS.next();
    var firstPerformBatch = IDS.next();
    var secondPerformBatch = IDS.next();
    var performed =
        concurrent(
            () ->
                post(
                    fixture.tenant(),
                    "/transfers:bulk-perform",
                    firstPerformBatch,
                    performanceBatch(
                        firstPerformBatch,
                        performOne,
                        reservation,
                        1,
                        fixture.professional(),
                        Instant.parse("2026-09-01T12:00:00Z"))),
            () ->
                post(
                    fixture.tenant(),
                    "/transfers:bulk-perform",
                    secondPerformBatch,
                    performanceBatch(
                        secondPerformBatch,
                        performTwo,
                        reservation,
                        1,
                        fixture.professional(),
                        Instant.parse("2026-09-01T12:00:00Z"))));
    assertThat(performed).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embryo_transfer WHERE embryo_id=?",
                Integer.class,
                ref.embryo()))
        .isEqualTo(1);

    var cancellable = winningReservations.get(1);
    var cancelRef = reservationRef(cancellable);
    var cancelKey = IDS.next();
    var cancelled =
        post(
            fixture.tenant(),
            "/transfer-reservations/" + cancellable + ":cancel",
            cancelKey,
            Map.of("expectedEmbryoVersion", 1, "reason", "Recipient unavailable"));
    ok(cancelled, 200);
    ok(
        post(
            fixture.tenant(),
            "/transfer-reservations/" + cancellable + ":cancel",
            cancelKey,
            Map.of("expectedEmbryoVersion", 1, "reason", "Recipient unavailable")),
        200);
    var replacement = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/transfers:bulk-reserve",
            reservationBatch(IDS.next(), replacement, cancelRef.embryo(), cancelRef.cycle(), 2)),
        200);
    var finalTransfer = IDS.next();
    var finalPerformBatch = IDS.next();
    var race =
        concurrent(
            () ->
                post(
                    fixture.tenant(),
                    "/transfers:bulk-perform",
                    finalPerformBatch,
                    performanceBatch(
                        finalPerformBatch,
                        finalTransfer,
                        replacement,
                        3,
                        fixture.professional(),
                        Instant.parse("2026-09-01T12:00:00Z"))),
            () ->
                post(
                    fixture.tenant(),
                    "/transfer-reservations/" + replacement + ":cancel",
                    IDS.next(),
                    Map.of("expectedEmbryoVersion", 3, "reason", "Concurrent cancellation")));
    assertThat(race).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    var finalState =
        jdbc.queryForMap(
            "SELECT r.status,e.availability_status FROM embryo_transfer_reservation r JOIN embryo e ON e.organization_id=r.organization_id AND e.id=r.embryo_id WHERE r.id=?",
            replacement);
    assertThat(finalState)
        .satisfiesAnyOf(
            state -> {
              assertThat(state.get("status")).isEqualTo("CONSUMED");
              assertThat(state.get("availability_status")).isEqualTo("TRANSFERRED");
            },
            state -> {
              assertThat(state.get("status")).isEqualTo("CANCELLED");
              assertThat(state.get("availability_status")).isEqualTo("AVAILABLE");
            });
  }

  @Test
  void tenantConstraintsAndAppendOnlyGrantsProtectHistoricalFacts() throws Exception {
    var fixture = fixture(1);
    var cycle = openCycle(fixture.tenant(), fixture.recipient(), LocalDate.of(2026, 9, 1));
    var reservation = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/transfers:bulk-reserve",
            reservationBatch(IDS.next(), reservation, fixture.embryos().getFirst(), cycle, 0)),
        200);
    var transfer = IDS.next();
    var rejectedThawed =
        performanceBatch(
            IDS.next(),
            IDS.next(),
            reservation,
            1,
            fixture.professional(),
            Instant.parse("2026-09-01T12:00:00Z"));
    @SuppressWarnings("unchecked")
    var thawedItem =
        new HashMap<>((Map<String, Object>) ((List<?>) rejectedThawed.get("items")).getFirst());
    thawedItem.put("origin", "THAWED");
    var thawedIntent = new HashMap<>(rejectedThawed);
    thawedIntent.put("items", List.of(thawedItem));
    ok(post(fixture.tenant(), "/transfers:bulk-perform", thawedIntent), 400);
    ok(
        post(
            fixture.tenant(),
            "/transfers:bulk-perform",
            performanceBatch(
                IDS.next(),
                transfer,
                reservation,
                1,
                fixture.professional(),
                Instant.parse("2026-09-01T12:00:00Z"))),
        200);
    var check = IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/pregnancy-checks:bulk",
            checkBatch(
                IDS.next(),
                checkItem(
                    check,
                    transfer,
                    Instant.parse("2026-10-01T12:00:00Z"),
                    "PREGNANT",
                    fixture.professional(),
                    null,
                    null))),
        200);

    var foreign = fixture(1);
    ok(get(foreign.tenant(), "/recipient-cycles/" + cycle), 404);
    ok(get(foreign.tenant(), "/transfers/" + transfer), 404);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO recipient_cycle(id,organization_id,recipient_animal_id,opened_on,status,origin_type,recorded_by,recorded_at) VALUES (?,? ,?,'2026-09-01','OPEN','MANUAL',?,now())",
                    IDS.next(),
                    foreign.tenant(),
                    fixture.recipient(),
                    foreign.actor()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    var duplicateReservation = IDS.next();
    jdbc.update(
        "INSERT INTO embryo_transfer_reservation(id,organization_id,embryo_id,recipient_cycle_id,status,reserved_by,reserved_at,ended_by,ended_at,end_reason) VALUES (?,?,?,?,'CONSUMED',?,now(),?,now(),'TRANSFER_PERFORMED')",
        duplicateReservation,
        fixture.tenant(),
        fixture.embryos().getFirst(),
        cycle,
        fixture.actor(),
        fixture.actor());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO embryo_transfer(id,organization_id,reservation_id,embryo_id,recipient_cycle_id,performed_at,performed_timezone,transfer_origin,operator_professional_id,origin_type,recorded_by,recorded_at) VALUES (?,?,?,?,?,now(),'UTC','FRESH',?,'MANUAL',?,now())",
                    IDS.next(),
                    fixture.tenant(),
                    duplicateReservation,
                    fixture.embryos().getFirst(),
                    cycle,
                    fixture.professional(),
                    fixture.actor()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    var readOnlySubject = "phase5-readonly-" + IDS.next();
    ok(
        post(
            fixture.tenant(),
            "/memberships",
            Map.of("id", IDS.next(), "subject", readOnlySubject, "role", "READ_ONLY")),
        201);
    ok(getAs(readOnlySubject, fixture.tenant(), "/recipient-cycles/" + cycle), 200);
    ok(
        postAs(
            readOnlySubject,
            fixture.tenant(),
            "/recipient-cycles",
            IDS.next(),
            Map.of(
                "id",
                IDS.next(),
                "recipientAnimalId",
                fixture.recipient(),
                "openedOn",
                "2026-12-01")),
        403);

    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE embryo_transfer SET notes='rewrite' WHERE id='" + transfer + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE pregnancy_check SET observations='rewrite' WHERE id='" + check + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
    }
    var current = get(fixture.tenant(), "/recipient-cycles/" + cycle);
    ok(current, 200);
    ok(
        post(
            fixture.tenant(),
            "/recipient-cycles/" + cycle + ":close",
            Map.of(
                "expectedVersion",
                0,
                "closedOn",
                "2026-11-01",
                "reason",
                "Outcome follow-up complete")),
        200);
    ok(
        post(
            fixture.tenant(),
            "/recipient-cycles/" + cycle + ":close",
            Map.of("expectedVersion", 0, "closedOn", "2026-11-01", "reason", "Repeated close")),
        409);
  }

  private Fixture fixture(int embryoCount) throws Exception {
    var tenant = IDS.next();
    var bootstrap =
        post(
            null,
            "/bootstrap/organizations",
            Map.of(
                "id",
                tenant,
                "legalName",
                "Phase 5 tenant",
                "taxId",
                "p5-" + tenant.toString().substring(0, 8),
                "timezone",
                "America/Sao_Paulo"));
    ok(bootstrap, 201);
    var actor = UUID.fromString(json.readTree(get(tenant, "/me").body()).get("actorId").asString());
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
                "Transfer lab",
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
            Map.of("id", professional, "name", "Veterinarian", "professionalType", "VETERINARIAN")),
        201);
    var donor = animal(tenant, "FEMALE", "Donor");
    var sire = animal(tenant, "MALE", "Sire");
    var recipient = animal(tenant, "FEMALE", "Recipient one");
    var secondRecipient = animal(tenant, "FEMALE", "Recipient two");
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
                        embryoCount,
                        "viable",
                        embryoCount)))),
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
                "LOT-" + semen.toString().substring(0, 8),
                "sireId",
                sire,
                "producerEstablishmentId",
                producer,
                "provenanceCode",
                "SUPPLIER_DOCUMENT",
                "verificationStatus",
                "DOCUMENTED")),
        201);
    var mating = IDS.next();
    var matingBatch = IDS.next();
    ok(
        post(
            tenant,
            "/matings:bulk",
            matingBatch,
            Map.of(
                "batchId",
                matingBatch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        IDS.next(),
                        "id",
                        mating,
                        "collectionId",
                        collection,
                        "semenBatchId",
                        semen,
                        "allocatedOocytes",
                        embryoCount,
                        "fertilizedAt",
                        Instant.EPOCH,
                        "method",
                        "IVF",
                        "responsibleProfessionalId",
                        professional)))),
        200);
    var embryos = new ArrayList<UUID>();
    var embryoItems = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < embryoCount; i++) {
      var embryo = IDS.next();
      embryos.add(embryo);
      embryoItems.add(
          Map.of(
              "itemId",
              IDS.next(),
              "id",
              embryo,
              "matingId",
              mating,
              "humanCode",
              "E-" + embryo,
              "identifiedAt",
              Instant.EPOCH));
    }
    var embryoBatch = IDS.next();
    ok(
        post(
            tenant,
            "/embryos:bulk",
            embryoBatch,
            Map.of("batchId", embryoBatch, "items", embryoItems)),
        200);
    return new Fixture(
        tenant,
        actor,
        donor,
        sire,
        recipient,
        secondRecipient,
        collection,
        semen,
        mating,
        professional,
        List.copyOf(embryos));
  }

  private UUID animal(UUID tenant, String sex, String name) throws Exception {
    var id = IDS.next();
    ok(post(tenant, "/animals", Map.of("id", id, "sex", sex, "name", name)), 201);
    return id;
  }

  private UUID openCycle(UUID tenant, UUID recipient, LocalDate openedOn) throws Exception {
    var id = IDS.next();
    ok(
        post(
            tenant,
            "/recipient-cycles",
            Map.of("id", id, "recipientAnimalId", recipient, "openedOn", openedOn)),
        201);
    return id;
  }

  private Map<String, Object> reservationBatch(
      UUID batch, UUID reservation, UUID embryo, UUID cycle, long version) {
    return reservationBatch(batch, List.of(reservationItem(reservation, embryo, cycle, version)));
  }

  private Map<String, Object> reservationBatch(UUID batch, List<Map<String, Object>> items) {
    return Map.of("batchId", batch, "items", items);
  }

  private Map<String, Object> reservationItem(
      UUID reservation, UUID embryo, UUID cycle, long version) {
    return Map.of(
        "itemId",
        IDS.next(),
        "reservationId",
        reservation,
        "embryoId",
        embryo,
        "recipientCycleId",
        cycle,
        "expectedEmbryoVersion",
        version);
  }

  private Map<String, Object> performanceBatch(
      UUID batch,
      UUID transfer,
      UUID reservation,
      long version,
      UUID professional,
      Instant performedAt) {
    return Map.of(
        "batchId",
        batch,
        "items",
        List.of(
            Map.of(
                "itemId",
                IDS.next(),
                "transferId",
                transfer,
                "reservationId",
                reservation,
                "expectedEmbryoVersion",
                version,
                "performedAt",
                performedAt,
                "timezone",
                "America/Sao_Paulo",
                "operatorProfessionalId",
                professional)));
  }

  private Map<String, Object> checkBatch(UUID batch, Map<String, Object> item) {
    return Map.of("batchId", batch, "items", List.of(item));
  }

  private Map<String, Object> checkItem(
      UUID id,
      UUID transfer,
      Instant checkedAt,
      String result,
      UUID professional,
      UUID supersedes,
      String reason) {
    var item = new HashMap<String, Object>();
    item.put("itemId", IDS.next());
    item.put("id", id);
    item.put("transferId", transfer);
    item.put("checkedAt", checkedAt);
    item.put("timezone", "America/Sao_Paulo");
    item.put("result", result);
    item.put("methodCode", "ULTRASOUND");
    item.put("professionalId", professional);
    if (supersedes != null) item.put("supersedesCheckId", supersedes);
    if (reason != null) item.put("correctionReason", reason);
    return item;
  }

  private ReservationRef reservationRef(UUID id) {
    return jdbc.queryForObject(
        "SELECT embryo_id,recipient_cycle_id FROM embryo_transfer_reservation WHERE id=?",
        (rs, row) ->
            new ReservationRef(
                rs.getObject("embryo_id", UUID.class),
                rs.getObject("recipient_cycle_id", UUID.class)),
        id);
  }

  private List<HttpResponse<String>> concurrent(
      Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
      throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
              });
      var b =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }
  }

  private void ok(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
  }

  private HttpResponse<String> post(UUID tenant, String path, Object body) throws Exception {
    UUID key = IDS.next();
    if (body instanceof Map<?, ?> values && values.get("batchId") instanceof UUID batchId)
      key = batchId;
    return post(tenant, path, key, body);
  }

  private HttpResponse<String> post(UUID tenant, String path, UUID key, Object body)
      throws Exception {
    return request("POST", tenant, path, key, body);
  }

  private HttpResponse<String> get(UUID tenant, String path) throws Exception {
    return request("GET", tenant, path, null, null);
  }

  private HttpResponse<String> getAs(String subject, UUID tenant, String path) throws Exception {
    return requestAs(subject, "GET", tenant, path, null, null);
  }

  private HttpResponse<String> postAs(
      String subject, UUID tenant, String path, UUID key, Object body) throws Exception {
    return requestAs(subject, "POST", tenant, path, key, body);
  }

  private HttpResponse<String> request(
      String method, UUID tenant, String path, UUID key, Object body) throws Exception {
    return requestAs("phase5-bootstrap", method, tenant, path, key, body);
  }

  private HttpResponse<String> requestAs(
      String subject, String method, UUID tenant, String path, UUID key, Object body)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(30))
            .header(
                "Authorization",
                "Bearer "
                    + TOKENS.token(
                        subject,
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

  private record Fixture(
      UUID tenant,
      UUID actor,
      UUID donor,
      UUID sire,
      UUID recipient,
      UUID secondRecipient,
      UUID collection,
      UUID semenBatch,
      UUID mating,
      UUID professional,
      List<UUID> embryos) {}

  private record ReservationRef(UUID embryo, UUID cycle) {}
}
