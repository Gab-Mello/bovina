package com.bovina.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bovina.cors")
public record CorsProperties(List<String> allowedOrigins) {
  public CorsProperties {
    allowedOrigins =
        allowedOrigins == null
            ? List.of()
            : allowedOrigins.stream().filter(s -> !s.isBlank()).toList();
    if (allowedOrigins.stream().anyMatch(s -> s.contains("*")))
      throw new IllegalArgumentException("CORS requires explicit origins");
  }
}
