package com.bovina.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ApplicationRuntimeIT {
  @LocalServerPort int port;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired Flyway flyway;
  @Autowired MeterRegistry metrics;
  @Autowired Clock clock;
  @MockitoBean JwtDecoder jwtDecoder;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
  }

  @Test
  void startsWithFlywayAndValidateWithoutArtificialProductionMigrations() {
    assertThat(flyway.info().applied()).isNotEmpty();
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    assertThat(entityManagerFactory.getMetamodel().getEntities())
        .extracting(jakarta.persistence.metamodel.EntityType::getName)
        .contains("Organization", "UserAccount", "OrganizationMembership")
        .doesNotContain("DatabaseRecord");
    assertThat(entityManagerFactory.getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
  }

  @Test
  void exposesProbesButNotAnonymousApplicationAccess() throws Exception {
    assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
    assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/clients").statusCode()).isEqualTo(401);
  }

  @Test
  void productionDoesNotServeApiDocumentationEvenToAuthenticatedCallers() throws Exception {
    var token = "authenticated-docs-check";
    Mockito.when(jwtDecoder.decode(token))
        .thenReturn(
            Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claim("iss", "https://identity.invalid")
                .claim("sub", "docs-check")
                .claim("aud", List.of("bovina-test"))
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .build());
    assertThat(get("/v3/api-docs", token).statusCode()).isEqualTo(404);
    assertThat(get("/swagger-ui/index.html", token).statusCode()).isEqualTo(404);
  }

  @Test
  void runtimeCannotCreateTablesOrBypassPermissions() throws Exception {
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("CREATE TABLE forbidden (id uuid)"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42501");
      try (var result =
          statement.executeQuery(
              "SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getBoolean(1) || result.getBoolean(2) || result.getBoolean(3)).isFalse();
      }
    }
  }

  @Test
  void recordsBasicMetricsAndUsesExplicitUtcClock() throws Exception {
    get("/actuator/health/readiness");
    assertThat(metrics.find("http.server.requests").timers()).isNotEmpty();
    assertThat(metrics.find("hikaricp.connections.max").gauge()).isNotNull();
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return get(path, null);
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
