package com.bovina.parties;

import static com.bovina.support.fixture.MasterDataFixtures.owner;
import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.ClientImportTransactions;
import com.bovina.parties.application.ClientImports;
import com.bovina.parties.domain.ClientImportBatch;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import com.bovina.support.integration.TestTenant;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class CounterpartyIT extends AuthenticatedIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private ClientImportTransactions importTransactions;
  @Autowired private ClientImports imports;

  @Test
  void productRolesShareOneInternalIdentityWithoutExposingPartyAsAnApiResource() throws Exception {
    var tenant = tenant("Counterparty Lab");
    var foreign = tenant("Foreign Counterparty Lab");
    var client = id();
    var registration =
        Map.of(
            "id",
            client,
            "type",
            "PERSON",
            "displayName",
            "Shared identity",
            "occurredAt",
            Instant.EPOCH);
    assertStatus(api.post(tenant.id(), "/clients", registration), 201);
    var roleRegistration = new HashMap<String, Object>(registration);
    roleRegistration.put("expectedVersion", 0);

    for (var path : List.of("/owners?scope=ANIMAL", "/suppliers", "/shipment-recipients")) {
      var key = id();
      var response = api.post(tenant.id(), path, key, roleRegistration);
      assertStatus(response, 201);
      assertThat(json.readTree(response.body()).path("version").asLong())
          .isEqualTo(((Number) roleRegistration.get("expectedVersion")).longValue() + 1);
      assertThat(json.readTree(api.post(tenant.id(), path, key, roleRegistration).body()))
          .isEqualTo(json.readTree(response.body()));
      roleRegistration.put(
          "expectedVersion", json.readTree(response.body()).path("version").asLong());
    }

    assertThat(jdbc.queryForObject("SELECT count(*) FROM party WHERE id=?", Integer.class, client))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party_role WHERE party_id=?", Integer.class, client))
        .isEqualTo(4);
    assertThat(api.get(tenant.id(), "/clients/" + client).body())
        .doesNotContain("Party", "roles", "organizationId");
    assertStatus(api.get(foreign.id(), "/suppliers/" + client), 404);
    assertThat(api.get(tenant.id(), "/clients?q=Shared&size=1").body()).contains("Shared identity");
    assertStatus(api.get(tenant.id(), "/parties/" + client), 404);
    assertStatus(
        api.post(
            tenant.id(),
            "/clients/" + client + ":archive",
            Map.of("expectedVersion", roleRegistration.get("expectedVersion"))),
        200);
    assertStatus(api.post(tenant.id(), "/owners?scope=MATERIAL", roleRegistration), 409);
    assertStatus(api.get(tenant.id(), "/clients/" + client), 200);
  }

  @Test
  void atomicImportValidationNeverCreatesAPartialClientBatch() throws Exception {
    var tenant = tenant("Atomic Import Lab");
    var batch = id();
    var client = id();
    var command =
        importBatch(batch, "ATOMIC", List.of(importRow(client, "Valid"), importRow(id(), " ")));

    var preview = api.post(tenant.id(), "/clients/imports:dry-run", command);
    var committed = api.post(tenant.id(), "/clients/imports", batch, command);

    assertStatus(preview, 200);
    assertThat(preview.body()).contains("VALID", "REJECTED", "INVALID_CLIENT_DETAILS");
    assertStatus(committed, 200);
    assertThat(committed.body())
        .contains("NOT_APPLIED", "REJECTED")
        .doesNotContain("\"status\":\"APPLIED\"");
    assertStatus(api.get(tenant.id(), "/clients/" + client), 404);
    assertThat(json.readTree(api.post(tenant.id(), "/clients/imports", batch, command).body()))
        .isEqualTo(json.readTree(committed.body()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM import_batch WHERE id=?", Integer.class, batch))
        .isEqualTo(1);
  }

  @Test
  void partialImportPersistsProvenanceAndReplaysImmutablePerItemResults() throws Exception {
    var tenant = tenant("Partial Import Lab");
    var batch = id();
    var client = id();
    var command =
        importBatch(
            batch, "PARTIAL", List.of(importRow(client, "Imported client"), importRow(id(), " ")));

    var result = api.post(tenant.id(), "/clients/imports", batch, command);

    assertStatus(result, 200);
    assertThat(result.body()).contains("APPLIED", "REJECTED");
    assertThat(api.get(tenant.id(), "/clients/" + client).body())
        .contains("IMPORT", "Imported client");
    assertThat(
            jdbc.queryForObject("SELECT import_batch_id FROM party WHERE id=?", UUID.class, client))
        .isEqualTo(batch);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='IMPORT'",
                Integer.class,
                client))
        .isEqualTo(1);
    assertThat(json.readTree(api.post(tenant.id(), "/clients/imports", batch, command).body()))
        .isEqualTo(json.readTree(result.body()));
    assertStatus(
        api.post(
            tenant.id(),
            "/clients/imports",
            batch,
            importBatch(batch, "PARTIAL", List.of(importRow(id(), "Changed")))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party WHERE import_batch_id=?", Integer.class, batch))
        .isEqualTo(1);
  }

  @Test
  void databaseConflictsAreReportedPerImportModeWithoutLeakingPartialAtomicWrites()
      throws Exception {
    var tenant = tenant("Import Conflict Lab");
    var foreign = tenant("Foreign Import Lab");
    var conflictingIdentity = owner(api, foreign.id(), "Foreign Owner");
    var validClient = id();
    var atomicBatch = id();
    var atomic =
        importBatch(
            atomicBatch,
            "ATOMIC",
            List.of(
                importRow(validClient, "Must roll back"),
                importRow(conflictingIdentity, "Conflicting identity")));
    var failed = api.post(tenant.id(), "/clients/imports", atomicBatch, atomic);
    assertStatus(failed, 200);
    assertThat(failed.body()).contains("ATOMIC_CONSTRAINT_CONFLICT");
    assertStatus(api.get(tenant.id(), "/clients/" + validClient), 404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=?", Integer.class, validClient))
        .isZero();

    var partialBatch = id();
    var partial =
        importBatch(
            partialBatch,
            "PARTIAL",
            List.of(
                importRow(conflictingIdentity, "Conflicting identity"),
                importRow(validClient, "Valid after rollback")));
    var result = api.post(tenant.id(), "/clients/imports", partialBatch, partial);
    assertStatus(result, 200);
    assertThat(result.body()).contains("CONSTRAINT_CONFLICT", "APPLIED");
    assertStatus(api.get(tenant.id(), "/clients/" + validClient), 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM import_item_result WHERE batch_id=?",
                Integer.class,
                partialBatch))
        .isEqualTo(2);
  }

  @Test
  void concurrentAtomicImportCreatesOneClientAndReplaysTheSameResults() throws Exception {
    var tenant = tenant("Concurrent Import Lab");
    var batch = id();
    var client = id();
    var command = importBatch(batch, "ATOMIC", List.of(importRow(client, "Concurrent import")));
    var start = new CyclicBarrier(3);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 3; index++) {
        requests.add(
            executor.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return api.post(tenant.id(), "/clients/imports", batch, command);
                }));
      }
      var responses = new ArrayList<JsonNode>();
      for (var request : requests) {
        var response = request.get(15, TimeUnit.SECONDS);
        assertStatus(response, 200);
        responses.add(json.readTree(response.body()));
      }
      assertThat(responses)
          .allSatisfy(response -> assertThat(response).isEqualTo(responses.getFirst()));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party WHERE import_batch_id=?", Integer.class, batch))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='IMPORT'",
                Integer.class,
                client))
        .isEqualTo(1);
  }

  @Test
  void interruptedPartialImportResumesOnlyUncommittedItems() throws Exception {
    var tenant = tenant("Resumable Import Lab");
    var firstClient = id();
    var secondClient = id();
    var batchId = id();
    var input =
        importBatch(
            batchId,
            "PARTIAL",
            List.of(importRow(firstClient, "First"), importRow(secondClient, "Second")));
    var batch = json.readValue(json.writeValueAsString(input), ClientImportBatch.class);
    var context = context(tenant);
    importTransactions.bind(context, batch);
    importTransactions.partialItem(context, batch, batch.items().getFirst());

    var resumed = imports.commit(context, batchId, batch);

    assertThat(resumed.items())
        .allSatisfy(
            item -> assertThat(item.status()).isEqualTo(ClientImportBatch.ItemStatus.APPLIED));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM party WHERE import_batch_id=?", Integer.class, batchId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='IMPORT'",
                Integer.class,
                firstClient))
        .isEqualTo(1);
  }

  @Test
  void declaredIdentifiersAreScopedPerCounterpartyAndSearchableWithoutDeduplication()
      throws Exception {
    var tenant = tenant("Identifier Lab");
    var foreign = tenant("Foreign Identifier Lab");
    var firstOwner = owner(api, tenant.id(), "Owner A");
    var secondOwner = owner(api, tenant.id(), "Owner B");
    var identifier = Map.of("id", id(), "type", "DECLARED_TAX_ID", "value", " 001-A ");
    assertStatus(api.post(tenant.id(), "/owners/" + firstOwner + "/identifiers", identifier), 201);
    var duplicate = Map.of("id", id(), "type", "DECLARED_TAX_ID", "value", "001-A");
    assertStatus(api.post(tenant.id(), "/owners/" + firstOwner + "/identifiers", duplicate), 409);
    assertStatus(api.post(tenant.id(), "/owners/" + secondOwner + "/identifiers", duplicate), 201);
    assertThat(api.get(tenant.id(), "/owners?scope=ANIMAL&q=001-A").body())
        .contains(firstOwner.toString(), secondOwner.toString());
    assertStatus(api.get(foreign.id(), "/owners/" + firstOwner + "/identifiers"), 404);
    assertThat(api.get(tenant.id(), "/owners/" + firstOwner + "/identifiers").body())
        .doesNotContain("partyId", "organizationId");
  }

  private com.bovina.platform.application.ExecutionContext context(TestTenant tenant) {
    return access.resolve(new AuthenticatedIdentity(issuer(), tenant.subject()), tenant.id(), id());
  }

  private Map<String, Object> importRow(UUID client, String name) {
    return Map.of(
        "itemId",
        id(),
        "id",
        client,
        "type",
        "PERSON",
        "displayName",
        name,
        "occurredAt",
        Instant.EPOCH);
  }

  private Map<String, Object> importBatch(
      UUID batch, String mode, List<Map<String, Object>> items) {
    return Map.of("batchId", batch, "mode", mode, "items", items);
  }
}
