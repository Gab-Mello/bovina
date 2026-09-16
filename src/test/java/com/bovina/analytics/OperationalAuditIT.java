package com.bovina.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalAuditIT extends AuthenticatedIntegrationTest {
  @Test
  void operationalAuditIsFilterableTenantScopedAndKeepsActorAndCorrelationWithoutPayloadDisclosure()
      throws Exception {
    var tenant = tenant("Auditable Lab");
    var client = id();
    var command =
        Map.of(
            "id",
            client,
            "type",
            "PERSON",
            "displayName",
            "Audited Client",
            "occurredAt",
            Instant.EPOCH);
    var created = api.post(tenant.id(), "/clients", client, command);
    assertStatus(created, 201);
    assertStatus(api.post(tenant.id(), "/clients", client, command), 201);
    var filter = "/audit-events?entityType=CLIENT&entityId=" + client + "&size=1";

    var result = api.get(tenant.id(), filter);
    assertStatus(result, 200);
    var items = json.readTree(result.body()).path("items");
    assertThat(items.size()).isEqualTo(1);
    var event = items.get(0);
    assertThat(event.path("action").asString()).isEqualTo("CREATE");
    assertThat(event.path("actorId").asString()).isEqualTo(tenant.actorId().toString());
    assertThat(event.path("entityId").asString()).isEqualTo(client.toString());
    assertThat(event.path("correlationId").asString())
        .isEqualTo(created.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertThat(event.has("previousState")).isFalse();
    assertThat(event.has("newState")).isFalse();
    assertThat(json.readTree(api.get(tenant.id(), filter + "&page=1").body()).path("items"))
        .isEmpty();
    var other = tenant("Other Auditable Lab");
    assertThat(json.readTree(api.get(other.id(), filter).body()).path("items")).isEmpty();
    assertStatus(api.get(tenant.id(), "/audit-events?size=101"), 422);
    assertStatus(api.get(tenant.id(), "/audit-events?entityType=invalid"), 422);
  }
}
