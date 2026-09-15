package com.bovina.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.platform.application.StableIds;
import com.bovina.support.integration.TestHttpClient;
import com.bovina.support.integration.TestTenant;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OpuFixtures {
  private static final StableIds IDS = new StableIds();

  private OpuFixtures() {}

  public static StartedOpu startedSession(TestHttpClient api, TestTenant tenant) throws Exception {
    var establishment = IDS.next();
    var property = IDS.next();
    var professional = IDS.next();
    var session = IDS.next();
    var address =
        Map.of(
            "addressLine", "Road 1",
            "municipality", "Test City",
            "state", "SP",
            "country", "BR");
    created(
        api.post(
            tenant.id(),
            "/establishments",
            Map.of(
                "id",
                establishment,
                "legalDisplayName",
                "OPU Lab",
                "operatingMode",
                "COMMERCIAL",
                "address",
                address)));
    created(
        api.post(
            tenant.id(),
            "/farm-properties",
            Map.of("id", property, "name", "Donor Farm", "address", address)));
    created(
        api.post(
            tenant.id(),
            "/professionals",
            Map.of(
                "id", professional, "name", "OPU Operator", "professionalType", "VETERINARIAN")));
    created(
        api.post(
            tenant.id(),
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
                "America/Sao_Paulo")));
    succeeded(
        api.post(tenant.id(), "/opu-sessions/" + session + ":start", Map.of("expectedVersion", 0)));
    return new StartedOpu(tenant, session, establishment, property, professional);
  }

  public static UUID donor(TestHttpClient api, TestTenant tenant, String name) throws Exception {
    var donor = IDS.next();
    created(api.post(tenant.id(), "/animals", Map.of("id", donor, "sex", "FEMALE", "name", name)));
    return donor;
  }

  public static Map<String, Object> collection(
      UUID collection, UUID donor, int totalRecovered, int viable) {
    return Map.of(
        "itemId", IDS.next(),
        "id", collection,
        "donorId", donor,
        "collectedAt", Instant.EPOCH,
        "totalRecovered", totalRecovered,
        "viable", viable);
  }

  public static Map<String, Object> collectionBatch(List<Map<String, Object>> items) {
    return Map.of("batchId", IDS.next(), "expectedSessionVersion", 1, "items", List.copyOf(items));
  }

  public static Map<String, Object> collectionBatch(Map<String, Object> item) {
    return collectionBatch(List.of(item));
  }

  public static Map<String, Object> correction(long version, int total, int viable) {
    return Map.of(
        "expectedVersion",
        version,
        "counts",
        Map.of("totalRecovered", total, "viable", viable),
        "reason",
        "Observed count correction");
  }

  private static void created(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  private static void succeeded(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  public record StartedOpu(
      TestTenant tenant, UUID session, UUID establishment, UUID property, UUID professional) {
    public String path() {
      return "/opu-sessions/" + session;
    }
  }
}
