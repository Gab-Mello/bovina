package com.bovina;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.TestDatabase;
import com.bovina.support.TrustedTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class SecurityAndOpenApiIT {
  private static final TrustedTokens TOKENS = new TrustedTokens();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    TestDatabase.properties(registry);
    registry.add("bovina.security.issuer", () -> TOKENS.issuer().toString());
    registry.add("bovina.security.jwk-set-uri", () -> TOKENS.jwks().toString());
  }

  @AfterAll
  static void closeKeys() {
    TOKENS.close();
  }

  @Test
  void acceptsSignedTokenAndProducesOpenApiWithoutBusinessEndpoints() throws Exception {
    var response =
        get(
            "/v3/api-docs",
            TOKENS.token(
                TOKENS.issuer().toString(), "bovina-test", Instant.now().plusSeconds(300)));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonMapper.builder().build().readTree(response.body()).path("openapi").asString())
        .startsWith("3.");
    assertThat(response.body()).doesNotContain("/api/v1/clients", "/fivs", "/bulls");
    assertThat(
            get(
                    "/swagger-ui/index.html",
                    TOKENS.token(
                        TOKENS.issuer().toString(), "bovina-test", Instant.now().plusSeconds(300)))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void rejectsMissingInvalidExpiredWrongIssuerAndWrongAudienceTokens() throws Exception {
    assertUnauthorized(null);
    assertUnauthorized("malformed-token");
    assertUnauthorized(
        TOKENS.token(TOKENS.issuer().toString(), "bovina-test", Instant.now().minusSeconds(120)));
    assertUnauthorized(
        TOKENS.token("https://untrusted.invalid", "bovina-test", Instant.now().plusSeconds(300)));
    assertUnauthorized(
        TOKENS.token(TOKENS.issuer().toString(), "different-api", Instant.now().plusSeconds(300)));
  }

  private void assertUnauthorized(String token) throws Exception {
    var response = get("/v3/api-docs", token);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    var body = JsonMapper.builder().build().readTree(response.body());
    assertThat(body.path("code").asString()).isEqualTo("AUTHENTICATION_REQUIRED");
    assertThat(body.path("traceId").asString())
        .isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertThat(response.body()).doesNotContain("malformed-token", "untrusted.invalid");
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
