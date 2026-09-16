package com.bovina.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.platform.application.StableIds;
import com.bovina.support.integration.*;
import java.time.Instant;
import java.util.*;

public final class DistributionFixtures {
  private static final StableIds IDS = new StableIds();
  public static final Instant STORED_AT = Instant.parse("2026-09-01T12:00:00Z");
  public static final Instant DISPATCHED_AT = Instant.parse("2026-09-02T12:00:00Z");

  private DistributionFixtures() {}

  public static Stock stock(TestHttpClient api, TestTenant tenant) throws Exception {
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    return stockedEmbryo(api, tenant, production, production.embryos().getFirst(), "Initial rack");
  }

  public static Stock stockedEmbryo(
      TestHttpClient api,
      TestTenant tenant,
      ProductionFixtures.EmbryoProduction production,
      UUID embryoId,
      String rackCode)
      throws Exception {
    var cryo = CryostorageFixtures.cryopreservedEmbryo(api, tenant, embryoId, production, IDS);
    var packaged = CryostorageFixtures.packagedEmbryo(api, tenant, cryo, IDS);
    var location =
        CryostorageFixtures.storageLocation(
            api, tenant.id(), production.inputs().opu().establishment(), rackCode, IDS);
    var move = IDS.next();
    var response =
        api.post(
            tenant.id(),
            "/inventory-movements",
            move,
            CryostorageFixtures.physicalMovement(
                move, packaged.packageId(), "STORE", null, location, 2, STORED_AT, null));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return new Stock(production, packaged, location);
  }

  public static UUID recipient(TestHttpClient api, TestTenant tenant) throws Exception {
    var id = IDS.next();
    var registered =
        api.post(
            tenant.id(),
            "/clients",
            id,
            Map.of(
                "id",
                id,
                "type",
                "COMPANY",
                "displayName",
                "Destination Lab",
                "occurredAt",
                Instant.EPOCH));
    // Client identity can hold the shipment-recipient role without a second master record.
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
    var role =
        api.post(
            tenant.id(),
            "/shipment-recipients",
            IDS.next(),
            Map.of(
                "id",
                id,
                "type",
                "COMPANY",
                "displayName",
                "Destination Lab",
                "expectedVersion",
                0,
                "occurredAt",
                Instant.EPOCH));
    assertThat(role.statusCode()).as(role.body()).isEqualTo(201);
    return id;
  }

  public static Map<String, Object> item(Stock stock) {
    return Map.of(
        "id",
        IDS.next(),
        "packageId",
        stock.packaged().packageId(),
        "expectedVersion",
        3,
        "expectedLocationId",
        stock.location());
  }

  public static Map<String, Object> preparation(
      UUID shipment,
      UUID recipient,
      Stock stock,
      List<Map<String, Object>> items,
      List<Map<String, Object>> documents) {
    return Map.of(
        "id",
        shipment,
        "establishmentId",
        stock.production().inputs().opu().establishment(),
        "recipientId",
        recipient,
        "propertyId",
        stock.production().inputs().opu().property(),
        "purpose",
        "OPERATIONAL_CUSTODY",
        "items",
        items,
        "documents",
        documents);
  }

  public static Map<String, Object> dispatch() {
    return Map.of("expectedVersion", 0, "occurredAt", DISPATCHED_AT);
  }

  public record Stock(
      ProductionFixtures.EmbryoProduction production,
      CryostorageFixtures.PackagedEmbryo packaged,
      UUID location) {}
}
