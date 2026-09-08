package com.bovina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.TestDatabase;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrameworkBaselineIT {
  @LocalServerPort int port;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired Flyway flyway;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
  }

  @Test
  void startsWithFlywayAndValidateWithoutArtificialProductionMigrations() {
    assertThat(flyway.info().applied()).isEmpty();
    assertThat(entityManagerFactory.getMetamodel().getEntities()).isEmpty();
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

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
