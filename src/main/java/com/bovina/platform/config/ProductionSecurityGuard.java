package com.bovina.platform.config;

import java.net.URI;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class ProductionSecurityGuard {
  ProductionSecurityGuard(
      Environment environment, SecurityProperties security, CorsProperties cors) {
    if (environment.acceptsProfiles(Profiles.of("dev"))
        || localEndpoint(security.issuer())
        || localEndpoint(security.jwkSetUri())
        || cors.allowedOrigins().stream()
            .map(URI::create)
            .anyMatch(ProductionSecurityGuard::localEndpoint)) {
      throw new IllegalStateException("Production cannot use local development authentication");
    }
  }

  private static boolean localEndpoint(URI uri) {
    var host = uri.getHost();
    return host != null
        && ("localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "[::1]".equals(host)
            || "dev-auth".equalsIgnoreCase(host));
  }
}
