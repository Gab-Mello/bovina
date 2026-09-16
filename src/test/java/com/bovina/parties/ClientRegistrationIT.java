package com.bovina.parties;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.integration.ClientApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientRegistrationIT extends ClientApiIntegrationTest {
  @Test
  void clientRoundTripIsTenantScopedAuditedAndIdempotent() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var id = id();
    var key = id();
    var body = clientBody(id, "Client A");
    var created = post(tenant, key, body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var view = json.readTree(created.body());
    assertThat(view.path("id").asString()).isEqualTo(id.toString());
    assertThat(view.path("version").asLong()).isZero();
    assertThat(view.path("originType").asString()).isEqualTo("MANUAL");
    assertThat(view.path("recordedBy").asString()).isEqualTo(tenant.actor().toString());
    assertThat(Instant.parse(view.path("recordedAt").asString())).isAfter(OCCURRED);
    assertThat(created.body()).doesNotContain("Party", "organizationId", "roles");
    assertThat(created.headers().firstValue("Location")).hasValue("/api/v1/clients/" + id);
    var replay = post(tenant, key, body);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(json.readTree(replay.body())).isEqualTo(view);
    var read = request("GET", "/api/v1/clients/" + id, tenant.token(), tenant.id(), null, null);
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(json.readTree(read.body())).isEqualTo(view);
    assertThat(read.headers().firstValue("ETag")).isEqualTo(created.headers().firstValue("ETag"));
    assertThat(count("party", "id", id)).isEqualTo(1);
    assertThat(count("audit_event", "entity_id", id)).isEqualTo(1);
    assertThat(count("idempotent_command", "idempotency_key", key)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM party_role WHERE organization_id=? AND party_id=?",
                String.class,
                tenant.id(),
                id))
        .isEqualTo("CLIENT");
    assertThat(
            jdbc.queryForObject("SELECT occurred_at FROM party WHERE id=?", Timestamp.class, id)
                .toInstant())
        .isEqualTo(OCCURRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT uuid_extract_version(id) FROM party WHERE id=?", Integer.class, id))
        .isEqualTo(7);
    var event = jdbc.queryForMap("SELECT * FROM audit_event WHERE entity_id=?", id);
    assertThat(event)
        .containsEntry("organization_id", tenant.id())
        .containsEntry("actor_id", tenant.actor())
        .containsEntry("action", "CREATE")
        .containsEntry("entity_type", "CLIENT")
        .containsEntry("entity_version", 0L);
    assertThat(event.get("correlation_id").toString())
        .isEqualTo(created.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertThat(event.values().toString()).doesNotContain("Client A", tenant.token());
  }

  @Test
  void bodyCannotAssignTenantOriginActorOrPersistenceState() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    for (var field : List.of("organizationId", "originType", "recordedBy", "status", "roles")) {
      var body = json.readTree(clientBody(id(), "A")).deepCopy();
      ((tools.jackson.databind.node.ObjectNode) body).put(field, "forged");
      assertThat(post(tenant, id(), body.toString()).statusCode()).as(field).isEqualTo(400);
    }
    assertThat(post(tenant, null, clientBody(id(), "A")).statusCode()).isEqualTo(400);
    assertThat(post(tenant, id(), clientBody(UUID.randomUUID(), "A")).statusCode()).isEqualTo(422);
    assertThat(post(tenant, id(), clientBody(id(), " ")).statusCode()).isEqualTo(400);
    assertThat(
            request("POST", "/api/v1/clients", null, tenant.id(), id(), clientBody(id(), "A"))
                .statusCode())
        .isEqualTo(401);
  }
}
