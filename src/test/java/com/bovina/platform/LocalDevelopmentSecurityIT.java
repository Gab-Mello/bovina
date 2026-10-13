package com.bovina.platform;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "bovina.cors.allowed-origins=http://localhost:5173,http://localhost:3000")
@ActiveProfiles("dev")
class LocalDevelopmentSecurityIT {
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
  void anonymousDocumentationRevealsTheClientContractWithoutInternalPartyResources()
      throws Exception {
    var response = get("/v3/api-docs", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonMapper.builder().build().readTree(response.body()).path("openapi").asString())
        .startsWith("3.");
    assertThat(response.body())
        .contains(
            "/api/v1/clients",
            "bearerAuth",
            "/api/v1/embryos/{id}/traceability",
            "/api/v1/embryos/{id}/timeline",
            "/api/v1/data-quality",
            "/api/v1/audit-events")
        .doesNotContain("/parties", "/fivs", "/bulls");
    assertThat(get("/swagger-ui/index.html", null).statusCode()).isEqualTo(200);
    assertThat(get("/swagger-ui.html", null).statusCode()).isEqualTo(302);
  }

  @Test
  void protectedApiRejectsMissingInvalidExpiredWrongIssuerAndWrongAudienceTokens()
      throws Exception {
    assertUnauthorized(null);
    assertUnauthorized("malformed-token");
    assertUnauthorized(
        TOKENS.token(TOKENS.issuer().toString(), "bovina-test", Instant.now().minusSeconds(120)));
    assertUnauthorized(
        TOKENS.token("https://untrusted.invalid", "bovina-test", Instant.now().plusSeconds(300)));
    assertUnauthorized(
        TOKENS.token(TOKENS.issuer().toString(), "different-api", Instant.now().plusSeconds(300)));
  }

  @Test
  void protectedApiRejectsMissingRequiredClaimsAndInvalidSignature() throws Exception {
    assertUnauthorized(
        TOKENS.token(TOKENS.issuer().toString(), null, Instant.now().plusSeconds(300)));
    assertUnauthorized(TOKENS.token(TOKENS.issuer().toString(), "bovina-test", null));
    assertUnauthorized(
        TOKENS.token(
            null, TOKENS.issuer().toString(), "bovina-test", Instant.now().plusSeconds(300)));
    try (var otherSigner = new TrustedTokens()) {
      assertUnauthorized(
          otherSigner.token(
              TOKENS.issuer().toString(), "bovina-test", Instant.now().plusSeconds(300)));
    }
  }

  @Test
  void validJwtStillNeedsMembershipForTenantContext() throws Exception {
    var token =
        TOKENS.token(TOKENS.issuer().toString(), "bovina-test", Instant.now().plusSeconds(300));
    assertThat(get("/api/v1/me/memberships", token).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/me", token).statusCode()).isEqualTo(403);
  }

  @Test
  void localFrontendOriginsCanPreflightTheApiWithoutOpeningCorsToOtherOrigins() throws Exception {
    for (var origin : new String[] {"http://localhost:5173", "http://localhost:3000"}) {
      var response = preflight(origin);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
    }
    var rejected = preflight("https://other.invalid");
    assertThat(rejected.statusCode()).isEqualTo(403);
    assertThat(rejected.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  private void assertUnauthorized(String token) throws Exception {
    var response = get("/api/v1/me/memberships", token);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    var body = JsonMapper.builder().build().readTree(response.body());
    assertThat(body.path("code").asString()).isEqualTo("AUTHENTICATION_REQUIRED");
    assertThat(body.path("traceId").asString())
        .isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertThat(response.body()).doesNotContain("malformed-token", "untrusted.invalid");
  }

  private HttpResponse<String> preflight(String origin) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/clients"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,x-organization-id")
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
