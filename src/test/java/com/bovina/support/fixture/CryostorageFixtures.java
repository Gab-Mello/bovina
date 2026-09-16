package com.bovina.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.integration.TestHttpClient;
import com.bovina.support.integration.TestTenant;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CryostorageFixtures {
  private CryostorageFixtures() {}

  public static CryoEmbryo cryopreservedEmbryo(
      TestHttpClient api,
      TestTenant tenant,
      UUID embryoId,
      ProductionFixtures.EmbryoProduction production,
      com.bovina.platform.application.StableIds ids)
      throws Exception {
    var scheme = ids.next();
    var version = ids.next();
    var stage = ids.next();
    var grade = ids.next();
    created(
        api.post(
            tenant.id(),
            "/assessment-schemes",
            Map.of(
                "id",
                scheme,
                "code",
                "CRYO_" + scheme.toString().substring(24).toUpperCase(),
                "name",
                "Cryostorage assessment")));
    created(
        api.post(
            tenant.id(),
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
                        "OBSERVED_STAGE",
                        "displayName",
                        "Observed stage",
                        "sortOrder",
                        1),
                    Map.of(
                        "id",
                        grade,
                        "dimension",
                        "QUALITY_GRADE",
                        "code",
                        "OBSERVED_GRADE",
                        "displayName",
                        "Observed grade",
                        "sortOrder",
                        1)))));
    var evaluation = ids.next();
    var evaluationBatch = ids.next();
    var evalResponse =
        api.post(
            tenant.id(),
            "/embryo-evaluations:bulk",
            evaluationBatch,
            Map.of(
                "batchId",
                evaluationBatch,
                "items",
                List.of(
                    Map.of(
                        "itemId",
                        ids.next(),
                        "id",
                        evaluation,
                        "embryoId",
                        embryoId,
                        "schemeVersionId",
                        version,
                        "developmentStageCodeId",
                        stage,
                        "qualityGradeCodeId",
                        grade,
                        "evaluatedAt",
                        Instant.EPOCH))));
    assertThat(evalResponse.statusCode()).as(evalResponse.body()).isEqualTo(200);
    var cryoEvent = ids.next();
    var cryoItem = ids.next();
    var command =
        Map.<String, Object>of(
            "eventId",
            cryoEvent,
            "establishmentId",
            production.inputs().opu().establishment(),
            "occurredAt",
            Instant.parse("2026-09-01T10:00:00Z"),
            "methodCode",
            "LAB_METHOD",
            "professionalId",
            production.professional(),
            "items",
            List.of(Map.of("id", cryoItem, "embryoId", embryoId, "expectedEmbryoVersion", 0)));
    var response = api.post(tenant.id(), "/cryopreservation-events", cryoEvent, command);
    created(response);
    return new CryoEmbryo(production, embryoId, evaluation, cryoEvent, cryoItem, command);
  }

  public static PackagedEmbryo packagedEmbryo(
      TestHttpClient api,
      TestTenant tenant,
      CryoEmbryo cryo,
      com.bovina.platform.application.StableIds ids)
      throws Exception {
    var packageId = ids.next();
    var packageItem = ids.next();
    created(
        api.post(
            tenant.id(),
            "/embryo-packages",
            packageId,
            Map.of(
                "id",
                packageId,
                "establishmentId",
                cryo.production().inputs().opu().establishment(),
                "packageCode",
                "PACK-" + packageId,
                "packagingType",
                "CONTAINER",
                "packagedAt",
                Instant.parse("2026-09-01T11:00:00Z"))));
    var added =
        api.post(
            tenant.id(),
            "/embryo-packages/" + packageId + "/items:bulk",
            Map.of(
                "expectedVersion",
                0,
                "items",
                List.of(Map.of("id", packageItem, "cryopreservationItemId", cryo.cryoItem()))));
    assertThat(added.statusCode()).as(added.body()).isEqualTo(200);
    var sealed =
        api.post(
            tenant.id(), "/embryo-packages/" + packageId + ":seal", Map.of("expectedVersion", 1));
    assertThat(sealed.statusCode()).as(sealed.body()).isEqualTo(200);
    return new PackagedEmbryo(cryo, packageId, packageItem);
  }

  public static UUID storageLocation(
      TestHttpClient api,
      UUID tenant,
      UUID establishment,
      String code,
      com.bovina.platform.application.StableIds ids)
      throws Exception {
    var location = ids.next();
    created(
        api.post(
            tenant,
            "/storage-locations",
            location,
            Map.of(
                "id",
                location,
                "establishmentId",
                establishment,
                "typeCode",
                "RACK",
                "code",
                code)));
    return location;
  }

  public static Map<String, Object> physicalMovement(
      UUID id,
      UUID packageId,
      String type,
      UUID from,
      UUID to,
      long version,
      Instant occurredAt,
      String reason) {
    var command = new HashMap<String, Object>();
    command.put("id", id);
    command.put("packageId", packageId);
    command.put("type", type);
    command.put("expectedLocationId", from);
    command.put("destinationId", to);
    command.put("expectedVersion", version);
    command.put("occurredAt", occurredAt);
    command.put("reason", reason);
    return command;
  }

  private static void created(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  public record CryoEmbryo(
      ProductionFixtures.EmbryoProduction production,
      UUID embryoId,
      UUID evaluationId,
      UUID cryoEvent,
      UUID cryoItem,
      Map<String, Object> command) {}

  public record PackagedEmbryo(CryoEmbryo cryo, UUID packageId, UUID packageItem) {}
}
