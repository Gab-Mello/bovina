package com.bovina.support.integration;

import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.CreateClient;
import com.bovina.parties.domain.ClientType;
import com.bovina.platform.application.CommandMetadata;
import com.bovina.platform.application.ExecutionContext;
import com.bovina.support.TestDatabase;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class ClientApiIntegrationTest extends AuthenticatedIntegrationTest {
  protected static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
  @Autowired protected TenantAccess access;

  protected final Tenant tenantWithRole(String role) throws Exception {
    var organizationId = id();
    var subject = "operator-" + id();
    var membership = id();
    assertStatus(
        api.post(
            null,
            "/bootstrap/organizations",
            Map.of(
                "id",
                organizationId,
                "legalName",
                "Client Test Organization",
                "taxId",
                organizationId.toString().replace("-", ""),
                "timezone",
                "America/Sao_Paulo")),
        201);
    assertStatus(
        api.post(
            organizationId,
            "/memberships",
            Map.of("id", membership, "subject", subject, "role", role)),
        201);
    var me = api.getAs(subject, organizationId, "/me");
    assertStatus(me, 200);
    return new Tenant(
        organizationId,
        UUID.fromString(json.readTree(me.body()).path("actorId").asString()),
        subject,
        api.token(subject),
        membership);
  }

  protected final String clientBody(UUID clientId, String displayName) {
    return json.writeValueAsString(
        Map.of(
            "id", clientId,
            "type", "PERSON",
            "displayName", displayName,
            "occurredAt", OCCURRED));
  }

  protected final String organizationBody(UUID organizationId) {
    return json.writeValueAsString(
        Map.of(
            "id",
            organizationId,
            "legalName",
            "Client Test Organization",
            "taxId",
            organizationId.toString().replace("-", ""),
            "timezone",
            "America/Sao_Paulo"));
  }

  protected final CreateClient command(UUID clientId, UUID key, String displayName) {
    return new CreateClient(
        clientId, ClientType.PERSON, displayName, new CommandMetadata(key, OCCURRED));
  }

  protected final ExecutionContext context(Tenant tenant) {
    return access.resolve(new AuthenticatedIdentity(issuer(), tenant.subject()), tenant.id(), id());
  }

  protected final String token(String subject) throws Exception {
    return api.token(subject);
  }

  protected final HttpResponse<String> post(Tenant tenant, UUID key, String body) throws Exception {
    return request("POST", "/api/v1/clients", tenant.token(), tenant.id(), key, body);
  }

  protected final HttpResponse<String> request(
      String method, String path, String token, UUID tenant, UUID key, String body)
      throws Exception {
    return api.requestWithToken(method, tenant, path, key, body, token);
  }

  protected final int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
  }

  protected final Connection migrationConnection() throws SQLException {
    return DriverManager.getConnection(
        TestDatabase.POSTGRES.getJdbcUrl(), "bovina_migration", TestDatabase.MIGRATION_PASSWORD);
  }

  protected final void awaitBlockedStatements(int minimum) throws Exception {
    PostgresTestAssertions.awaitLockWait("", minimum);
  }

  protected final void awaitBlockedStatements(String queryFragment, int minimum) throws Exception {
    PostgresTestAssertions.awaitLockWait(queryFragment, minimum);
  }

  protected record Tenant(UUID id, UUID actor, String subject, String token, UUID membership) {}
}
