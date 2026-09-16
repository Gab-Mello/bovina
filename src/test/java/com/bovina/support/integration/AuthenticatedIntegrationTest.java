package com.bovina.support.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.TestDatabase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(IntegrationTestRuntime.class)
public abstract class AuthenticatedIntegrationTest {
  @LocalServerPort private int port;
  @Autowired protected JsonMapper json;
  @Autowired protected JdbcTemplate jdbc;
  protected TestHttpClient api;

  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
    registry.add("bovina.security.issuer", () -> IntegrationTestRuntime.TOKENS.issuer().toString());
    registry.add(
        "bovina.security.jwk-set-uri", () -> IntegrationTestRuntime.TOKENS.jwks().toString());
    registry.add("bovina.bootstrap.enabled", () -> true);
    registry.add(
        "bovina.bootstrap.issuer", () -> IntegrationTestRuntime.TOKENS.issuer().toString());
    registry.add("bovina.bootstrap.subject", () -> "integration-bootstrap");
    registry.add("bovina.cors.allowed-origins", () -> "https://ui.invalid");
  }

  @BeforeEach
  final void configureHttpClient() {
    api = new TestHttpClient(port, json);
  }

  protected final UUID id() {
    return IntegrationTestRuntime.IDS.next();
  }

  protected final String issuer() {
    return IntegrationTestRuntime.TOKENS.issuer().toString();
  }

  protected final TestTenant tenant() throws Exception {
    return tenant("Integration Test Organization");
  }

  protected final TestTenant tenant(String legalName) throws Exception {
    var organizationId = id();
    var created =
        api.post(
            null,
            "/bootstrap/organizations",
            Map.of(
                "id",
                organizationId,
                "legalName",
                legalName,
                "taxId",
                organizationId.toString().replace("-", ""),
                "timezone",
                "America/Sao_Paulo"));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var me = api.get(organizationId, "/me");
    assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
    return new TestTenant(
        organizationId,
        UUID.fromString(json.readTree(me.body()).path("actorId").asString()),
        "integration-bootstrap",
        api.token("integration-bootstrap"));
  }

  protected static void assertStatus(java.net.http.HttpResponse<String> response, int expected) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
  }
}
