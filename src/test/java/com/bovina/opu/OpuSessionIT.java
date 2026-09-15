package com.bovina.opu;

import static com.bovina.support.fixture.OpuFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.application.CollectionAllocationBoundary;
import com.bovina.opu.application.CollectionBatch;
import com.bovina.opu.application.RecordCollections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OpuSessionIT extends OpuIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private CollectionAllocationBoundary allocation;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private RecordCollections recorder;
  @Autowired private jakarta.persistence.EntityManagerFactory entityManagers;

  @Test
  void localSessionCompletesWithoutFutureLineageAndFreezesTheDonorSnapshot() throws Exception {
    var opu = startedSession(api, tenant("Local OPU Lab"));
    var donor = donor(api, opu.tenant(), "Donor A");
    var collection = id();
    var command = collectionBatch(collection(collection, donor, 10, 8));
    var key = (java.util.UUID) command.get("batchId");

    var recorded = api.post(opu.tenant().id(), opu.path() + "/collections:bulk", key, command);

    assertStatus(recorded, 200);
    var before = api.get(opu.tenant().id(), "/oocyte-collections/" + collection);
    assertStatus(before, 200);
    assertThat(before.body()).contains("MANUAL").doesNotContain("sire", "semen", "mating", "Fiv");
    assertThat(api.get(opu.tenant().id(), "/animals?role=DONOR").body()).contains(donor.toString());
    var completionKey = id();
    var completion =
        api.post(
            opu.tenant().id(),
            opu.path() + ":complete",
            completionKey,
            Map.of("expectedVersion", 1));
    assertStatus(completion, 200);
    assertThat(
            json.readTree(
                api.post(
                        opu.tenant().id(),
                        opu.path() + ":complete",
                        completionKey,
                        Map.of("expectedVersion", 1))
                    .body()))
        .isEqualTo(json.readTree(completion.body()));
    var donorSnapshot =
        api.get(opu.tenant().id(), "/oocyte-collections/" + collection + "/donor-snapshot");
    jdbc.update("UPDATE animal SET name='Changed later' WHERE id=?", donor);
    assertThat(
            api.get(opu.tenant().id(), "/oocyte-collections/" + collection + "/donor-snapshot")
                .body())
        .isEqualTo(donorSnapshot.body());
    assertThat(api.get(opu.tenant().id(), opu.path() + "/summary").body())
        .contains("Donor Farm", "\"totalRecovered\":10", "\"viable\":8");
    assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", key, command), 200);
    assertStatus(
        api.post(
            opu.tenant().id(),
            opu.path() + "/collections:bulk",
            collectionBatch(collection(id(), donor(api, opu.tenant(), "Donor B"), 2, 1))),
        409);
    assertStatus(
        api.post(
            opu.tenant().id(),
            "/oocyte-collections/" + collection + ":correct",
            correction(1, 8, 7)),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='RECORD'",
                Integer.class,
                collection))
        .isEqualTo(1);
    var context =
        access.resolve(
            new AuthenticatedIdentity(issuer(), opu.tenant().subject()), opu.tenant().id(), id());
    var capacity =
        new TransactionTemplate(transactions)
            .execute(status -> allocation.lockCompleted(context, collection));
    assertThat(capacity.availableAfter(0)).isEqualTo(8);
    assertThat(capacity.donorId()).isEqualTo(donor);
  }

  @Test
  void bulkDryRunExplainsInvalidCountsAndTheWriteRollsBackEveryItem() throws Exception {
    var opu = startedSession(api, tenant("OPU Bulk Lab"));
    var validCollection = id();
    var command =
        collectionBatch(
            List.of(
                collection(validCollection, donor(api, opu.tenant(), "Donor A"), 4, 3),
                collection(id(), donor(api, opu.tenant(), "Donor B"), 1, 2)));

    var preview = api.post(opu.tenant().id(), opu.path() + "/collections:dry-run", command);
    var write = api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command);

    assertStatus(preview, 200);
    assertThat(preview.body()).contains("VALID", "INVALID_OOCYTE_COUNTS", "REJECTED");
    assertStatus(write, 422);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_collection WHERE opu_session_id=?",
                Integer.class,
                opu.session()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=?",
                Integer.class,
                validCollection))
        .isZero();
  }

  @Test
  void correctionUsesOptimisticVersioningAndPreservesOriginalProvenance() throws Exception {
    var opu = startedSession(api, tenant("OPU Correction Lab"));
    var collection = id();
    assertStatus(
        api.post(
            opu.tenant().id(),
            opu.path() + "/collections:bulk",
            collectionBatch(collection(collection, donor(api, opu.tenant(), "Donor A"), 7, 5))),
        200);
    var provenance =
        json.readTree(api.get(opu.tenant().id(), "/oocyte-collections/" + collection).body())
            .path("provenance");

    assertStatus(
        api.post(
            opu.tenant().id(),
            "/oocyte-collections/" + collection + ":correct",
            correction(0, 6, 4)),
        200);
    assertStatus(
        api.post(
            opu.tenant().id(),
            "/oocyte-collections/" + collection + ":correct",
            correction(0, 6, 4)),
        409);

    var corrected =
        json.readTree(api.get(opu.tenant().id(), "/oocyte-collections/" + collection).body());
    assertThat(corrected.path("provenance")).isEqualTo(provenance);
    assertThat(corrected.path("registration").path("counts").path("viable").asInt()).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE entity_id=? AND action='CORRECT' AND reason IS NOT NULL AND previous_state IS NOT NULL",
                Integer.class,
                collection))
        .isEqualTo(1);
  }

  @Test
  void importAndApiOriginsRequireEvidenceAndReplayKeepsTheRecordedProvenance() throws Exception {
    var opu = startedSession(api, tenant("OPU Provenance Lab"));
    var document = id();
    assertStatus(
        api.post(
            opu.tenant().id(),
            "/document-references",
            Map.of(
                "id", document,
                "type", "DECLARED_OPU_SOURCE",
                "reference", "source/ref",
                "revision", "1")),
        201);
    for (var origin : List.of("IMPORT", "API")) {
      var collection = id();
      var command =
          new HashMap<String, Object>(
              collectionBatch(
                  collection(collection, donor(api, opu.tenant(), origin + " Donor"), 2, 1)));
      var source = new HashMap<String, Object>();
      source.put("origin", origin);
      source.put("sourceDocumentId", document);
      if (origin.equals("API")) {
        source.put("apiClientId", id());
      }
      command.put("source", source);

      assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command), 200);
      var recorded = api.get(opu.tenant().id(), "/oocyte-collections/" + collection);
      assertThat(recorded.body()).contains(origin, document.toString());
      assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command), 200);
      assertThat(api.get(opu.tenant().id(), "/oocyte-collections/" + collection).body())
          .isEqualTo(recorded.body());
    }
    var invalid =
        new HashMap<String, Object>(
            collectionBatch(collection(id(), donor(api, opu.tenant(), "Invalid Donor"), 1, 1)));
    invalid.put("source", Map.of("origin", "IMPORT"));
    assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", invalid), 400);
  }

  @Test
  void readOnlyMembershipCannotRecordCollectionsThroughHttpOrApplicationService() throws Exception {
    var opu = startedSession(api, tenant("Restricted OPU Lab"));
    var subject = "read-only-opu-user-" + id();
    assertStatus(
        api.post(
            opu.tenant().id(),
            "/memberships",
            Map.of("id", id(), "subject", subject, "role", "READ_ONLY")),
        201);
    var context =
        access.resolve(new AuthenticatedIdentity(issuer(), subject), opu.tenant().id(), id());
    var command =
        json.readValue(
            json.writeValueAsString(
                collectionBatch(
                    collection(id(), donor(api, opu.tenant(), "Restricted Donor"), 1, 1))),
            CollectionBatch.class);

    assertThatThrownBy(() -> recorder.record(context, command.batchId(), opu.session(), command))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    assertStatus(
        api.postAs(
            subject,
            opu.tenant().id(),
            opu.path() + ":complete",
            id(),
            Map.of("expectedVersion", 1)),
        403);
  }

  @Test
  void completionUsesBoundedQueriesAndPagedCollectionReads() throws Exception {
    var opu = startedSession(api, tenant("High-volume OPU Lab"));
    var collections = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < 20; index++) {
      collections.add(collection(id(), donor(api, opu.tenant(), "Donor " + index), 2, 1));
    }
    var command = Map.of("batchId", id(), "expectedSessionVersion", 1, "items", collections);
    assertStatus(api.post(opu.tenant().id(), opu.path() + "/collections:bulk", command), 200);
    var statistics = entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    statistics.clear();

    assertStatus(
        api.post(opu.tenant().id(), opu.path() + ":complete", Map.of("expectedVersion", 1)), 200);

    assertThat(statistics.getPrepareStatementCount()).isLessThan(20);
    assertThat(statistics.getCollectionFetchCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oocyte_donor_snapshot WHERE organization_id=?",
                Integer.class,
                opu.tenant().id()))
        .isEqualTo(20);
    var page = api.get(opu.tenant().id(), opu.path() + "/collections?size=3&page=1");
    assertStatus(page, 200);
    assertThat(json.readTree(page.body()).path("items").size()).isEqualTo(3);
  }
}
