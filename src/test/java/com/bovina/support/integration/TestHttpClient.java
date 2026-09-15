package com.bovina.support.integration;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public final class TestHttpClient {
  private final int port;
  private final JsonMapper json;

  TestHttpClient(int port, JsonMapper json) {
    this.port = port;
    this.json = json;
  }

  public HttpResponse<String> get(UUID organizationId, String path) throws Exception {
    return request("integration-bootstrap", "GET", organizationId, path, null, null);
  }

  public HttpResponse<String> getAs(String subject, UUID organizationId, String path)
      throws Exception {
    return request(subject, "GET", organizationId, path, null, null);
  }

  public HttpResponse<String> post(UUID organizationId, String path, Object body) throws Exception {
    UUID key = IntegrationTestRuntime.IDS.next();
    if (body instanceof java.util.Map<?, ?> values
        && values.get("batchId") instanceof UUID batchId) {
      key = batchId;
    }
    return post(organizationId, path, key, body);
  }

  public HttpResponse<String> post(UUID organizationId, String path, UUID key, Object body)
      throws Exception {
    return request("integration-bootstrap", "POST", organizationId, path, key, body);
  }

  public HttpResponse<String> postAs(
      String subject, UUID organizationId, String path, UUID key, Object body) throws Exception {
    return request(subject, "POST", organizationId, path, key, body);
  }

  public HttpResponse<String> request(
      String subject,
      String method,
      UUID organizationId,
      String path,
      UUID idempotencyKey,
      Object body)
      throws Exception {
    return requestWithToken(method, organizationId, path, idempotencyKey, body, token(subject));
  }

  public HttpResponse<String> requestWithToken(
      String method,
      UUID organizationId,
      String path,
      UUID idempotencyKey,
      Object body,
      String bearerToken)
      throws Exception {
    var uri = path.startsWith("/api/") ? path : "/api/v1" + path;
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + uri))
            .timeout(Duration.ofSeconds(30))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(
                        body instanceof String text ? text : json.writeValueAsString(body)));
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    if (organizationId != null) {
      builder.header("X-Organization-ID", organizationId.toString());
    }
    if (idempotencyKey != null) {
      builder.header("Idempotency-Key", idempotencyKey.toString());
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return IntegrationTestRuntime.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> preflight(String path, String origin, String requestedMethod)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", origin)
            .header("Access-Control-Request-Method", requestedMethod)
            .header(
                "Access-Control-Request-Headers", "authorization,idempotency-key,x-organization-id")
            .build();
    return IntegrationTestRuntime.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  public String token(String subject) throws Exception {
    return IntegrationTestRuntime.TOKENS.token(
        subject,
        IntegrationTestRuntime.TOKENS.issuer().toString(),
        "bovina-test",
        Instant.now().plusSeconds(600));
  }
}
