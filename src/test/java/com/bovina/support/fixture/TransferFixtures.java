package com.bovina.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.platform.application.StableIds;
import com.bovina.support.integration.TestHttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TransferFixtures {
  private static final StableIds IDS = new StableIds();

  private TransferFixtures() {}

  public static UUID openCycle(TestHttpClient api, UUID tenant, UUID recipient, LocalDate openedOn)
      throws Exception {
    var cycle = IDS.next();
    assertThat(
            api.post(
                    tenant,
                    "/recipient-cycles",
                    Map.of("id", cycle, "recipientAnimalId", recipient, "openedOn", openedOn))
                .statusCode())
        .isEqualTo(201);
    return cycle;
  }

  public static Reservation reservation(
      TestHttpClient api, UUID tenant, UUID embryo, UUID recipientCycle, long expectedEmbryoVersion)
      throws Exception {
    var reservation = IDS.next();
    var batch = IDS.next();
    var command =
        reservationBatch(
            batch, reservationItem(reservation, embryo, recipientCycle, expectedEmbryoVersion));
    var response = api.post(tenant, "/transfers:bulk-reserve", batch, command);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return new Reservation(reservation, batch, command);
  }

  public static PerformedTransfer perform(
      TestHttpClient api,
      UUID tenant,
      UUID reservation,
      long expectedEmbryoVersion,
      UUID professional,
      Instant performedAt)
      throws Exception {
    var transfer = IDS.next();
    var batch = IDS.next();
    var command =
        performanceBatch(
            batch, transfer, reservation, expectedEmbryoVersion, professional, performedAt);
    var response = api.post(tenant, "/transfers:bulk-perform", batch, command);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return new PerformedTransfer(transfer, batch, command);
  }

  public static Map<String, Object> reservationBatch(UUID batch, Map<String, Object> item) {
    return reservationBatch(batch, List.of(item));
  }

  public static Map<String, Object> reservationBatch(UUID batch, List<Map<String, Object>> items) {
    return Map.of("batchId", batch, "items", List.copyOf(items));
  }

  public static Map<String, Object> reservationItem(
      UUID reservation, UUID embryo, UUID cycle, long expectedEmbryoVersion) {
    return Map.of(
        "itemId", IDS.next(),
        "reservationId", reservation,
        "embryoId", embryo,
        "recipientCycleId", cycle,
        "expectedEmbryoVersion", expectedEmbryoVersion);
  }

  public static Map<String, Object> performanceBatch(
      UUID batch,
      UUID transfer,
      UUID reservation,
      long expectedEmbryoVersion,
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
                expectedEmbryoVersion,
                "performedAt",
                performedAt,
                "timezone",
                "America/Sao_Paulo",
                "operatorProfessionalId",
                professional)));
  }

  public static Map<String, Object> checkBatch(UUID batch, Map<String, Object> item) {
    return Map.of("batchId", batch, "items", List.of(item));
  }

  public static Map<String, Object> checkItem(
      UUID check,
      UUID transfer,
      Instant checkedAt,
      String result,
      UUID professional,
      UUID supersedes,
      String correctionReason) {
    var item = new HashMap<String, Object>();
    item.put("itemId", IDS.next());
    item.put("id", check);
    item.put("transferId", transfer);
    item.put("checkedAt", checkedAt);
    item.put("timezone", "America/Sao_Paulo");
    item.put("result", result);
    item.put("methodCode", "ULTRASOUND");
    item.put("professionalId", professional);
    if (supersedes != null) {
      item.put("supersedesCheckId", supersedes);
    }
    if (correctionReason != null) {
      item.put("correctionReason", correctionReason);
    }
    return item;
  }

  public record Reservation(UUID id, UUID batchId, Map<String, Object> command) {}

  public record PerformedTransfer(UUID id, UUID batchId, Map<String, Object> command) {}
}
