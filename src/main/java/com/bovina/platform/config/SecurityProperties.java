package com.bovina.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("bovina.security")
public record SecurityProperties(
    @NotNull URI issuer, @NotNull URI jwkSetUri, @NotBlank String audience) {}
