package com.bovina.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.parties.application.CreateClientService;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.support.TestDatabase;
import com.bovina.support.integration.ClientApiIntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdentityAuthorizationIT extends ClientApiIntegrationTest {
  @Autowired private CreateClientService createClient;

  @Test
  void readOnlyAndRevokedMembershipCannotWriteEvenWithValidJwt() throws Exception {
    var reader = tenantWithRole("READ_ONLY");
    assertThat(post(reader, id(), clientBody(id(), "Blocked")).statusCode()).isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships",
                    reader.token(),
                    reader.id(),
                    null,
                    json.writeValueAsString(
                        Map.of("id", id(), "subject", "attempted-admin", "role", "ORG_ADMIN")))
                .statusCode())
        .isEqualTo(403);
    assertThatThrownBy(() -> createClient.create(context(reader), command(id(), id(), "Blocked")))
        .isInstanceOf(ApplicationFailure.class);
    var forgedPermissions =
        new ExecutionContext(reader.id(), reader.actor(), Set.of("client:create"), id());
    assertThatThrownBy(() -> createClient.create(forgedPermissions, command(id(), id(), "Blocked")))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    var revoke = json.writeValueAsString(Map.of("expectedVersion", 0, "reason", "Access removed"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + reader.membership() + ":revoke",
                    token("integration-bootstrap"),
                    reader.id(),
                    null,
                    revoke)
                .statusCode())
        .isEqualTo(204);
    assertThat(request("GET", "/api/v1/me", reader.token(), reader.id(), null, null).statusCode())
        .isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + reader.membership() + ":revoke",
                    token("integration-bootstrap"),
                    reader.id(),
                    null,
                    revoke)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM audit_event WHERE entity_id=? AND action='REVOKE'",
                String.class,
                reader.membership()))
        .isEqualTo("Access removed");
  }

  @Test
  void unknownIdentityAndCrossTenantMembershipAdministrationAreDenied() throws Exception {
    var a = tenantWithRole("OPERATOR");
    var b = tenantWithRole("OPERATOR");
    assertThat(request("GET", "/api/v1/me", token("unregistered"), a.id(), null, null).statusCode())
        .isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    "/api/v1/bootstrap/organizations",
                    a.token(),
                    null,
                    null,
                    organizationBody(id()))
                .statusCode())
        .isEqualTo(403);
    var revoke = json.writeValueAsString(Map.of("expectedVersion", 0, "reason", "Attempt"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + a.membership() + ":revoke",
                    token("integration-bootstrap"),
                    b.id(),
                    null,
                    revoke)
                .statusCode())
        .isEqualTo(404);
    assertThat(
            request("GET", "/api/v1/me", token("integration-bootstrap"), null, null, null)
                .statusCode())
        .isEqualTo(422);
    assertThat(
            request("GET", "/api/v1/me", token("integration-bootstrap"), a.id(), null, null)
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void expiredMembershipDisabledUserAndSuspendedOrganizationAreDenied() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    jdbc.update(
        "UPDATE organization_membership SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id=?",
        tenant.membership());
    assertThat(request("GET", "/api/v1/me", tenant.token(), tenant.id(), null, null).statusCode())
        .isEqualTo(403);
    jdbc.update(
        "UPDATE organization_membership SET valid_until=NULL WHERE id=?", tenant.membership());
    jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", tenant.actor());
    assertThat(request("GET", "/api/v1/me", tenant.token(), tenant.id(), null, null).statusCode())
        .isEqualTo(403);
    jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?", tenant.actor());
    jdbc.update("UPDATE organization SET status='SUSPENDED' WHERE id=?", tenant.id());
    assertThat(request("GET", "/api/v1/me", tenant.token(), tenant.id(), null, null).statusCode())
        .isEqualTo(403);
  }

  @Test
  void corsAllowsOnlyConfiguredUiWithoutSessionCookies() throws Exception {
    for (var origin : List.of("https://ui.invalid", "https://attacker.invalid")) {
      var response = api.preflight("/api/v1/clients", origin, "POST");
      assertThat(response.statusCode()).isEqualTo(origin.contains("attacker") ? 403 : 200);
      assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
      if (!origin.contains("attacker"))
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
    }
  }

  @Test
  void identityPairAndMembershipAreUniqueAndInvalidGrantsAreRejected() throws Exception {
    var tenant = tenantWithRole("OPERATOR");
    var duplicate =
        json.writeValueAsString(
            Map.of("id", id(), "subject", tenant.subject(), "role", "OPERATOR"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships",
                    token("integration-bootstrap"),
                    tenant.id(),
                    null,
                    duplicate)
                .statusCode())
        .isEqualTo(409);
    var expired =
        json.writeValueAsString(
            Map.of(
                "id",
                id(),
                "subject",
                "expired-" + id(),
                "role",
                "OPERATOR",
                "validUntil",
                Instant.EPOCH));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships",
                    token("integration-bootstrap"),
                    tenant.id(),
                    null,
                    expired)
                .statusCode())
        .isEqualTo(422);
    var noVersion = json.writeValueAsString(Map.of("reason", "Missing version"));
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships/" + tenant.membership() + ":revoke",
                    token("integration-bootstrap"),
                    tenant.id(),
                    null,
                    noVersion)
                .statusCode())
        .isEqualTo(400);
    jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", tenant.actor());
    assertThat(
            request(
                    "POST",
                    "/api/v1/memberships",
                    token("integration-bootstrap"),
                    tenant.id(),
                    null,
                    duplicate)
                .statusCode())
        .isEqualTo(403);
    try (var connection = TestDatabase.runtimeConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO user_account(id,issuer,subject,status,created_at) VALUES (?,?,?,'ACTIVE',now())")) {
      statement.setObject(1, id());
      statement.setString(2, issuer());
      statement.setString(3, tenant.subject());
      assertThatThrownBy(statement::executeUpdate)
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23505");
      statement.setString(2, "https://other-issuer.invalid");
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }
}
