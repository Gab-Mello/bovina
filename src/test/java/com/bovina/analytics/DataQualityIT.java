package com.bovina.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.TestDatabase;
import com.bovina.support.fixture.DistributionFixtures;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class DataQualityIT extends AuthenticatedIntegrationTest {
  @Test
  void missingAssessmentIsAnInformationalObservedGapAndReadsCreateNoFacts() throws Exception {
    var tenant = tenant("Assessment Visibility Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 2);
    var before =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE organization_id=?", Long.class, tenant.id());

    var page = api.get(tenant.id(), "/data-quality?size=1");
    assertStatus(page, 200);
    var issue = json.readTree(page.body()).path("items").get(0);
    assertThat(issue.path("code").asString()).isEqualTo("ASSESSMENT_NOT_RECORDED");
    assertThat(issue.path("severity").asString()).isEqualTo("INFO");
    assertThat(issue.has("compliant")).isFalse();
    var next = api.get(tenant.id(), "/data-quality?page=1&size=1");
    assertStatus(next, 200);
    assertThat(json.readTree(next.body()).path("items").get(0).path("subjectId").asString())
        .isNotEqualTo(issue.path("subjectId").asString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=?",
                Long.class,
                tenant.id()))
        .isEqualTo(before);
    var other = tenant("Other Assessment Lab");
    assertThat(json.readTree(api.get(other.id(), "/data-quality").body()).path("items")).isEmpty();
    assertStatus(api.get(tenant.id(), "/data-quality?size=101"), 422);
  }

  @Test
  void inventoryProjectionDriftIsDerivedFromLedgerWithoutChangingPhysicalHistory()
      throws Exception {
    var tenant = tenant("Projection Integrity Lab");
    var stock = DistributionFixtures.stock(api, tenant);
    var packageId = stock.packaged().packageId();
    var filter = "/data-quality?q=INVENTORY_PROJECTION_MISMATCH";
    assertThat(json.readTree(api.get(tenant.id(), filter).body()).path("items")).isEmpty();
    var pg = TestDatabase.POSTGRES;
    try (var connection =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var update =
            connection.prepareStatement(
                "UPDATE embryo_package SET last_movement_sequence=? WHERE organization_id=? AND id=?")) {
      update.setLong(1, 99);
      update.setObject(2, tenant.id());
      update.setObject(3, packageId);
      update.executeUpdate();
      try {
        var response = api.get(tenant.id(), filter);
        assertStatus(response, 200);
        var issues = json.readTree(response.body()).path("items");
        assertThat(issues.size()).isEqualTo(1);
        assertThat(issues.get(0).path("subjectId").asString()).isEqualTo(packageId.toString());
        assertThat(issues.get(0).path("severity").asString()).isEqualTo("ERROR");
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM inventory_movement WHERE organization_id=? AND package_id=?",
                    Integer.class,
                    tenant.id(),
                    packageId))
            .isEqualTo(1);
      } finally {
        update.setLong(1, 1);
        update.executeUpdate();
      }
    }
    assertThat(json.readTree(api.get(tenant.id(), filter).body()).path("items")).isEmpty();
  }
}
