package com.bovina.cryostorage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.fixture.CryostorageFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class CryopreservationIT extends AuthenticatedIntegrationTest {
  @Test
  void cryopreservationIsAnImmutableLineageFactAndRetryDoesNotDuplicateIt() throws Exception {
    var tenant = tenant("Cryopreservation Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var cryo =
        CryostorageFixtures.cryopreservedEmbryo(
            api,
            tenant,
            production.embryos().getFirst(),
            production,
            new com.bovina.platform.application.StableIds());

    assertThat(api.get(tenant.id(), "/embryos/" + cryo.embryoId()).body())
        .contains("\"preservation\":\"CRYOPRESERVED\"");
    var replay =
        api.post(tenant.id(), "/cryopreservation-events", cryo.cryoEvent(), cryo.command());
    assertStatus(replay, 201);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM cryopreservation_item WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=?",
                Integer.class,
                tenant.id(),
                cryo.cryoEvent()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT origin_type FROM cryopreservation_event WHERE organization_id=? AND id=?",
                String.class,
                tenant.id(),
                cryo.cryoEvent()))
        .isEqualTo("MANUAL");

    var changed = new HashMap<>(cryo.command());
    changed.put("methodCode", "OTHER_METHOD");
    assertStatus(api.post(tenant.id(), "/cryopreservation-events", cryo.cryoEvent(), changed), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM cryopreservation_item WHERE organization_id=? AND embryo_id=?",
                Integer.class,
                tenant.id(),
                cryo.embryoId()))
        .isEqualTo(1);
  }
}
