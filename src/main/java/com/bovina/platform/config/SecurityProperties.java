package com.bovina.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("bovina.security")
public record SecurityProperties(
    @NotNull URI issuer, @NotNull URI jwkSetUri, @NotBlank String audience) {
  @AssertTrue(
      message = "Identity endpoints must be absolute HTTP(S) URIs without embedded credentials")
  public boolean isEndpointFormatValid() {
    return validEndpoint(issuer) && validEndpoint(jwkSetUri);
  }

  private static boolean validEndpoint(URI uri) {
    return uri != null
        && uri.getHost() != null
        && uri.getUserInfo() == null
        && uri.getFragment() == null
        && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
  }
}
