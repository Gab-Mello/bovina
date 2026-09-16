package com.bovina.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.platform.application.StableIds;
import com.bovina.support.integration.TestHttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class MasterDataFixtures {
  private static final StableIds IDS = new StableIds();

  private MasterDataFixtures() {}

  public static UUID animal(TestHttpClient api, UUID tenant, String name) throws Exception {
    var animal = IDS.next();
    created(api.post(tenant, "/animals", Map.of("id", animal, "sex", "FEMALE", "name", name)));
    return animal;
  }

  public static UUID establishment(TestHttpClient api, UUID tenant, String name) throws Exception {
    var establishment = IDS.next();
    created(
        api.post(
            tenant,
            "/establishments",
            Map.of(
                "id",
                establishment,
                "legalDisplayName",
                name,
                "operatingMode",
                "COMMERCIAL",
                "address",
                address())));
    return establishment;
  }

  public static UUID professional(TestHttpClient api, UUID tenant, String name) throws Exception {
    var professional = IDS.next();
    created(
        api.post(
            tenant,
            "/professionals",
            Map.of(
                "id", professional,
                "name", name,
                "professionalType", "VETERINARIAN")));
    return professional;
  }

  public static UUID credential(TestHttpClient api, UUID tenant, UUID professional, UUID document)
      throws Exception {
    var credential = IDS.next();
    created(
        api.post(
            tenant,
            "/professionals/" + professional + "/credentials",
            Map.of(
                "id",
                credential,
                "issuer",
                "CRMV declared",
                "jurisdiction",
                "SP",
                "number",
                "123",
                "period",
                Map.of("from", "2026-01-01"),
                "documentId",
                document)));
    return credential;
  }

  public static UUID owner(TestHttpClient api, UUID tenant, String name) throws Exception {
    var owner = IDS.next();
    created(
        api.post(
            tenant,
            "/owners?scope=ANIMAL",
            Map.of(
                "id", owner, "type", "PERSON", "displayName", name, "occurredAt", Instant.EPOCH)));
    return owner;
  }

  public static Map<String, Object> address() {
    return Map.of(
        "addressLine", "Road 1",
        "municipality", "Test City",
        "state", "SP",
        "country", "BR");
  }

  public static Map<String, Object> property(UUID id, UUID owner) {
    return Map.of("id", id, "name", "Recipient Farm", "ownerId", owner, "address", address());
  }

  private static void created(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }
}
