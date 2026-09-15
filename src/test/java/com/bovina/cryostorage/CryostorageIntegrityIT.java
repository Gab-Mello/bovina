package com.bovina.cryostorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class CryostorageIntegrityIT extends AuthenticatedIntegrationTest {
  @Test
  void tenantForeignKeysAndActiveMembershipUniquenessProtectPackageLineage() throws Exception {
    var lab = tenant("Package Integrity Lab");
    var other = tenant("Other Package Tenant");
    var production = ProductionFixtures.freshEmbryos(api, lab, 1);
    var ids = new com.bovina.platform.application.StableIds();
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api, lab, production.embryos().getFirst(), production, ids);
    var packaged = CryostorageFixtures.packagedEmbryo(api, lab, cryo, ids);
    assertStatus(api.get(other.id(), "/embryo-packages/" + packaged.packageId()), 404);
    var labPage = api.get(lab.id(), "/embryo-packages?page=0&size=1");
    var otherPage = api.get(other.id(), "/embryo-packages?page=0&size=1");
    assertStatus(labPage, 200);
    assertStatus(otherPage, 200);
    assertThat(labPage.body()).contains(packaged.packageId().toString());
    assertThat(otherPage.body()).doesNotContain(packaged.packageId().toString());
    assertStatus(api.get(other.id(), "/inventory-movements/packages/" + packaged.packageId()), 404);

    var duplicatePackage = id();
    assertStatus(
        api.post(
            lab.id(),
            "/embryo-packages",
            duplicatePackage,
            Map.of(
                "id",
                duplicatePackage,
                "establishmentId",
                production.inputs().opu().establishment(),
                "packageCode",
                "SECOND-" + duplicatePackage,
                "packagingType",
                "CONTAINER",
                "packagedAt",
                Instant.parse("2026-09-01T12:00:00Z"))),
        201);
    var duplicatedMember = id();
    assertStatus(
        api.post(
            lab.id(),
            "/embryo-packages/" + duplicatePackage + "/items:bulk",
            Map.of(
                "expectedVersion",
                0,
                "items",
                List.of(
                    Map.of("id", duplicatedMember, "cryopreservationItemId", cryo.cryoItem())))),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM package_item WHERE organization_id=? AND embryo_id=? AND removed_at IS NULL",
                Integer.class,
                lab.id(),
                cryo.embryoId()))
        .isEqualTo(1);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO storage_location(id,organization_id,establishment_id,type_code,code,status,created_at,created_by) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                    id(),
                    other.id(),
                    production.inputs().opu().establishment(),
                    "RACK",
                    "ILLEGAL_RACK",
                    Timestamp.from(Instant.parse("2026-09-01T12:00:00Z")),
                    other.actorId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
