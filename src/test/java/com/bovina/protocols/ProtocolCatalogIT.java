package com.bovina.protocols;

import static com.bovina.support.fixture.MasterDataFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.bovina.identity.application.*;
import com.bovina.protocols.application.Protocols;
import com.bovina.support.TestDatabase;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProtocolCatalogIT extends AuthenticatedIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private Protocols protocols;

  private com.bovina.platform.application.ExecutionContext context(UUID tenant) {
    return access.resolve(
        new AuthenticatedIdentity(issuer(), "integration-bootstrap"), tenant, id());
  }

  @Test
  void publishedProtocolsStayImmutableAndKeepTheirExactPurposeAndRevision() throws Exception {
    var a = tenant().id();
    var b = tenant().id();
    var definition = id();
    var id = id();
    var created =
        api.post(
            a,
            "/protocol-definitions",
            Map.of(
                "id",
                definition,
                "purpose",
                "OOCYTE_TRANSPORT",
                "name",
                "Declared transport protocol"));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var path = "/protocol-definitions/" + definition + "/versions";
    var input =
        Map.of(
            "id",
            id,
            "revision",
            "1.0",
            "effectivePeriod",
            Map.of("from", "2026-01-01", "until", "2027-01-01"),
            "contentReference",
            "lab-controlled-reference/1.0",
            "checksum",
            "a".repeat(64));
    assertThat(api.post(b, path, input).statusCode()).isEqualTo(404);
    var key = id();
    var published = api.post(a, path, key, input);
    assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
    assertThat(json.readTree(api.post(a, path, key, input).body()))
        .isEqualTo(json.readTree(published.body()));
    var duplicate = new HashMap<String, Object>(input);
    duplicate.put("id", id());
    assertThat(api.post(a, path, duplicate).statusCode()).isEqualTo(409);
    var next = new HashMap<String, Object>(input);
    next.put("id", id());
    next.put("revision", "2.0");
    next.put("checksum", "b".repeat(64));
    assertThat(api.post(a, path, next).statusCode()).isEqualTo(201);
    var context =
        access.resolve(
            new com.bovina.identity.application.AuthenticatedIdentity(
                issuer(), "integration-bootstrap"),
            a,
            id());
    assertThat(
            protocols
                .requireApplicable(context, id, "OOCYTE_TRANSPORT", LocalDate.of(2026, 6, 1))
                .revision())
        .isEqualTo("1.0");
    assertThatThrownBy(
            () ->
                protocols.requireApplicable(
                    context, id, "CRYOPRESERVATION", LocalDate.of(2026, 6, 1)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var sql :
          List.of(
              "UPDATE protocol_version SET checksum='" + "c".repeat(64) + "' WHERE id='" + id + "'",
              "DELETE FROM protocol_version WHERE id='" + id + "'"))
        assertThatThrownBy(() -> statement.executeUpdate(sql))
            .isInstanceOf(java.sql.SQLException.class)
            .extracting("SQLState")
            .isEqualTo("42501");
    }
    assertThat(
            api.post(
                    a,
                    "/protocol-definitions/" + definition + ":deactivate",
                    Map.of("expectedVersion", 0))
                .statusCode())
        .isEqualTo(200);
    assertThat(json.readTree(api.get(a, path + "/" + id).body()))
        .isEqualTo(json.readTree(published.body()));
    assertThatThrownBy(
            () ->
                protocols.requireApplicable(
                    context, id, "OOCYTE_TRANSPORT", LocalDate.of(2026, 6, 1)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class);
    assertThat(api.get(b, path + "/" + id).statusCode()).isEqualTo(404);
  }

  @Test
  void withdrawingOneProtocolRevisionPreservesItsBytesAndOtherVersions() throws Exception {
    var tenant = tenant().id();
    var definition = id();
    var first = id();
    var second = id();
    assertThat(
            api.post(
                    tenant,
                    "/protocol-definitions",
                    Map.of("id", definition, "purpose", "OOCYTE_TRANSPORT", "name", "Transport"))
                .statusCode())
        .isEqualTo(201);
    var path = "/protocol-definitions/" + definition + "/versions";
    var publication =
        Map.of(
            "id",
            first,
            "revision",
            "1",
            "effectivePeriod",
            Map.of("from", "2026-01-01"),
            "contentReference",
            "reference/1",
            "checksum",
            "a".repeat(64));
    var original = api.post(tenant, path, publication);
    assertThat(original.statusCode()).isEqualTo(201);
    var next = new HashMap<String, Object>(publication);
    next.put("id", second);
    next.put("revision", "2");
    assertThat(api.post(tenant, path, next).statusCode()).isEqualTo(201);
    var key = id();
    var reason = Map.of("reason", "Superseded operational instruction");
    var withdrawal = api.post(tenant, path + "/" + first + ":deactivate", key, reason);
    assertThat(withdrawal.statusCode()).as(withdrawal.body()).isEqualTo(200);
    assertThat(
            json.readTree(api.post(tenant, path + "/" + first + ":deactivate", key, reason).body()))
        .isEqualTo(json.readTree(withdrawal.body()));
    assertThat(json.readTree(api.get(tenant, path + "/" + first).body()))
        .isEqualTo(json.readTree(original.body()));
    var context = context(tenant);
    assertThatThrownBy(
            () ->
                protocols.requireApplicable(
                    context, first, "OOCYTE_TRANSPORT", LocalDate.of(2026, 2, 1)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("PROTOCOL_VERSION_WITHDRAWN");
    assertThat(
            protocols
                .requireApplicable(context, second, "OOCYTE_TRANSPORT", LocalDate.of(2026, 2, 1))
                .id())
        .isEqualTo(second);
  }
}
