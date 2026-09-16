package com.bovina.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.TrustedTokens;
import com.bovina.support.fixture.ProductionFixtures;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalMetricsIT extends AuthenticatedIntegrationTest {
  @Test
  void onlyExplicitOperationsScopeCanReadGlobalMetricsAndRealCommandsEmitSignals()
      throws Exception {
    var tenant = tenant("Measured Lab");
    var production = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var embryo = production.embryos().getFirst();
    var key = id();
    var command = Map.of("expectedVersion", 0, "reason", "Observed non-transferable material");
    assertStatus(api.post(tenant.id(), "/embryos/" + embryo + ":discard", key, command), 200);
    assertStatus(api.post(tenant.id(), "/embryos/" + embryo + ":discard", key, command), 200);
    assertStatus(
        api.post(
            tenant.id(),
            "/embryos/" + embryo + ":discard",
            key,
            Map.of("expectedVersion", 0, "reason", "Different intent")),
        409);
    assertStatus(api.requestWithToken("GET", null, "/actuator/metrics", null, null, null), 401);
    assertStatus(api.get(tenant.id(), "/actuator/metrics"), 403);
    try (var tokens = new TrustedTokens()) {
      // Use the already trusted signer, not this unrelated key, to protect issuer/signature checks.
      assertStatus(
          api.requestWithToken(
              "GET",
              null,
              "/actuator/metrics",
              null,
              null,
              tokens.token(
                  "operations",
                  issuer(),
                  "bovina-test",
                  Instant.now().plusSeconds(300),
                  "observability:read")),
          401);
    }
    var token = api.operationsToken();
    var metrics = api.requestWithToken("GET", null, "/actuator/metrics", null, null, token);
    assertStatus(metrics, 200);
    assertThat(json.readTree(metrics.body()).path("names").toString())
        .contains("bovina.command.attempts", "http.server.requests", "hikaricp");
    var timer =
        api.requestWithToken(
            "GET",
            null,
            "/actuator/metrics/bovina.command.attempts?tag=operation:EMBRYO_DISCARD_V1&tag=outcome:REPLAYED",
            null,
            null,
            token);
    assertStatus(timer, 200);
    assertThat(json.readTree(timer.body()).path("measurements").get(0).path("value").asDouble())
        .isGreaterThanOrEqualTo(1);
  }
}
