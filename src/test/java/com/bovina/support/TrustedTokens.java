package com.bovina.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

public final class TrustedTokens implements AutoCloseable {
  private final RSAKey key;
  private final HttpServer server;

  public TrustedTokens() {
    try {
      key = new RSAKeyGenerator(2048).keyID("phase0-test").generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] bytes =
                new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
              body.write(bytes);
            }
          });
      server.start();
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot initialize test signing keys", exception);
    }
  }

  public URI issuer() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public URI jwks() {
    return issuer().resolve("/jwks");
  }

  public String token(String issuer, String audience, Instant expiresAt) throws Exception {
    return token("test-operator", issuer, audience, expiresAt);
  }

  public String token(String subject, String issuer, String audience, Instant expiresAt)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .issueTime(Date.from(Instant.now().minusSeconds(300)));
    if (audience != null) claims.audience(audience);
    if (expiresAt != null) claims.expirationTime(Date.from(expiresAt));
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
