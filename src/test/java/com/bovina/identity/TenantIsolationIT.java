package com.bovina.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.parties.application.GetClient;
import com.bovina.parties.infrastructure.PartyRepository;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.support.TestDatabase;
import com.bovina.support.integration.ClientApiIntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantIsolationIT extends ClientApiIntegrationTest {
  @Autowired private PartyRepository parties;
  @Autowired private GetClient getClient;

  @Test
  void crossTenantAccessFailsAtApiApplicationAndRepository() throws Exception {
    var a = tenantWithRole("OPERATOR");
    var b = tenantWithRole("OPERATOR");
    var id = id();
    assertThat(post(a, id(), clientBody(id, "Private A")).statusCode()).isEqualTo(201);
    var foreign = request("GET", "/api/v1/clients/" + id, b.token(), b.id(), null, null);
    var missing = request("GET", "/api/v1/clients/" + id(), b.token(), b.id(), null, null);
    assertThat(foreign.statusCode()).isEqualTo(404);
    assertThat(json.readTree(foreign.body()).path("code").asString())
        .isEqualTo(json.readTree(missing.body()).path("code").asString());
    assertThat(foreign.body()).doesNotContain("Private A", a.id().toString());
    assertThat(parties.findByOrganizationIdAndId(b.id(), id)).isEmpty();
    assertThatThrownBy(() -> getClient.get(context(b), id))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("CLIENT_NOT_FOUND");
    var forged =
        new ExecutionContext(b.id(), a.actor(), Set.of("client:read", "client:create"), id());
    assertThatThrownBy(() -> getClient.get(forged, id))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    assertThat(request("GET", "/api/v1/clients/" + id, b.token(), a.id(), null, null).statusCode())
        .isEqualTo(403);
    assertThat(request("GET", "/api/v1/parties/" + id, a.token(), a.id(), null, null).statusCode())
        .isEqualTo(404);
  }

  @Test
  void compoundForeignKeysPreventCrossTenantAssociations() throws Exception {
    var a = tenantWithRole("OPERATOR");
    var b = tenantWithRole("OPERATOR");
    var id = id();
    var key = id();
    assertThat(post(a, key, clientBody(id, "A")).statusCode()).isEqualTo(201);
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      for (var sql :
          List.of(
              "INSERT INTO party_role VALUES ('" + b.id() + "','" + id + "','CLIENT')",
              "UPDATE party SET recorded_by='" + b.actor() + "' WHERE id='" + id + "'",
              "UPDATE idempotent_command SET organization_id='"
                  + b.id()
                  + "',actor_id='"
                  + b.actor()
                  + "' WHERE idempotency_key='"
                  + key
                  + "'")) {
        assertThatThrownBy(() -> statement.execute(sql))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("23503");
      }
      assertThatThrownBy(
              () ->
                  statement.execute("UPDATE party SET organization_id=NULL WHERE id='" + id + "'"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23502");
    }
  }
}
