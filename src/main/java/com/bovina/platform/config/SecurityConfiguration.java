package com.bovina.platform.config;

import com.bovina.platform.api.ApiProblems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).build();
    OAuth2TokenValidator<Jwt> requiredClaims =
        jwt ->
            jwt.getAudience() != null
                    && jwt.getAudience().contains(properties.audience())
                    && jwt.getExpiresAt() != null
                    && jwt.getSubject() != null
                    && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid required claims", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuer().toString()), requiredClaims));
    return decoder;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ApiProblems problems)
      throws Exception {
    AuthenticationEntryPoint unauthenticated =
        (request, response, exception) -> {
          response.setHeader("WWW-Authenticate", "Bearer");
          problems.write(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request, response);
        };
    AccessDeniedHandler forbidden =
        (request, response, exception) ->
            problems.write(HttpStatus.FORBIDDEN, "ACCESS_DENIED", request, response);
    // This API accepts bearer tokens, never browser session cookies.
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    // Global operational metrics are not granted by tenant membership.
                    .requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                    .hasAuthority("SCOPE_observability:read")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(forbidden))
        .build();
  }
}
