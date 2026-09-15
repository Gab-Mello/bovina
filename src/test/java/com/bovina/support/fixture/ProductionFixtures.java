package com.bovina.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.platform.application.StableIds;
import com.bovina.support.integration.TestHttpClient;
import com.bovina.support.integration.TestTenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductionFixtures {
  private static final StableIds IDS = new StableIds();

  private ProductionFixtures() {}

  public static OpuProduction completedOpu(TestHttpClient api, TestTenant tenant, int viableOocytes)
      throws Exception {
    var started = OpuFixtures.startedSession(api, tenant);
    var donor = OpuFixtures.donor(api, tenant, "Donor A");
    var collection = IDS.next();
    var collectionBatch =
        OpuFixtures.collectionBatch(
            OpuFixtures.collection(collection, donor, viableOocytes, viableOocytes));
    succeeded(
        api.post(
            tenant.id(),
            started.path() + "/collections:bulk",
            (UUID) collectionBatch.get("batchId"),
            collectionBatch));
    succeeded(api.post(tenant.id(), started.path() + ":complete", Map.of("expectedVersion", 1)));
    return new OpuProduction(
        tenant,
        started.establishment(),
        started.property(),
        started.professional(),
        donor,
        started.session(),
        collection,
        viableOocytes);
  }

  public static EmbryoProduction freshEmbryos(
      TestHttpClient api, TestTenant tenant, int embryoCount) throws Exception {
    return freshEmbryos(api, tenant, embryoCount, embryoCount);
  }

  public static EmbryoProduction freshEmbryos(
      TestHttpClient api, TestTenant tenant, int allocatedOocytes, int identifiedEmbryos)
      throws Exception {
    var inputs = fertilizationInputs(api, tenant, allocatedOocytes);
    var opu = inputs.opu();
    var semenBatch = inputs.semenBatch();
    var mating = IDS.next();
    var matingBatch = IDS.next();
    succeeded(
        api.post(
            tenant.id(),
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
                        opu.collection(),
                        "semenBatchId",
                        semenBatch,
                        "allocatedOocytes",
                        allocatedOocytes,
                        "fertilizedAt",
                        Instant.EPOCH,
                        "method",
                        "IVF",
                        "responsibleProfessionalId",
                        opu.professional())))));
    var embryos = new ArrayList<UUID>();
    var embryoItems = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < identifiedEmbryos; index++) {
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
              "FRESH-" + embryo,
              "identifiedAt",
              Instant.EPOCH));
    }
    var embryoBatch = IDS.next();
    succeeded(
        api.post(
            tenant.id(),
            "/embryos:bulk",
            embryoBatch,
            Map.of("batchId", embryoBatch, "items", embryoItems)));
    return new EmbryoProduction(inputs, mating, List.copyOf(embryos));
  }

  public static FertilizationInputs fertilizationInputs(
      TestHttpClient api, TestTenant tenant, int viableOocytes) throws Exception {
    var opu = completedOpu(api, tenant, viableOocytes);
    var sire = animal(api, tenant.id(), "MALE", "Sire A");
    var producer = IDS.next();
    created(
        api.post(
            tenant.id(),
            "/external-establishments",
            Map.of(
                "id",
                producer,
                "name",
                "Semen Center",
                "establishmentType",
                "SEMEN_CENTER",
                "country",
                "BR",
                "verificationStatus",
                "DOCUMENTED")));
    var semenBatch = IDS.next();
    created(
        api.post(
            tenant.id(),
            "/semen-batches",
            Map.of(
                "id",
                semenBatch,
                "batchCode",
                "LOT-" + semenBatch.toString().substring(0, 8),
                "sireId",
                sire,
                "producerEstablishmentId",
                producer,
                "provenanceCode",
                "SUPPLIER_DOCUMENT",
                "verificationStatus",
                "DOCUMENTED")));
    return new FertilizationInputs(opu, sire, producer, semenBatch);
  }

  public static UUID animal(TestHttpClient api, UUID tenant, String sex, String name)
      throws Exception {
    var animal = IDS.next();
    created(api.post(tenant, "/animals", Map.of("id", animal, "sex", sex, "name", name)));
    return animal;
  }

  private static void created(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  private static void succeeded(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  public record OpuProduction(
      TestTenant tenant,
      UUID establishment,
      UUID property,
      UUID professional,
      UUID donor,
      UUID session,
      UUID collection,
      int viableOocytes) {}

  public record FertilizationInputs(
      OpuProduction opu, UUID sire, UUID producerEstablishment, UUID semenBatch) {
    public TestTenant tenant() {
      return opu.tenant();
    }

    public UUID donor() {
      return opu.donor();
    }

    public UUID collection() {
      return opu.collection();
    }

    public UUID professional() {
      return opu.professional();
    }
  }

  public record EmbryoProduction(FertilizationInputs inputs, UUID mating, List<UUID> embryos) {
    public TestTenant tenant() {
      return inputs.tenant();
    }

    public UUID donor() {
      return inputs.donor();
    }

    public UUID sire() {
      return inputs.sire();
    }

    public UUID collection() {
      return inputs.collection();
    }

    public UUID professional() {
      return inputs.professional();
    }

    public UUID semenBatch() {
      return inputs.semenBatch();
    }
  }
}
