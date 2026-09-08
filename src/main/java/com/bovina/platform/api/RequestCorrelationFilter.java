package com.bovina.platform.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Correlation-ID");
    String correlation =
        supplied != null && supplied.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
            ? supplied.toLowerCase(java.util.Locale.ROOT)
            : UUID.randomUUID().toString();
    request.setAttribute("traceId", correlation);
    response.setHeader("X-Correlation-ID", correlation);
    long start = System.nanoTime();
    String previous = MDC.get("traceId");
    MDC.put("traceId", correlation);
    try {
      try {
        chain.doFilter(request, response);
      } finally {
        LOG.atInfo()
            .addKeyValue("httpMethod", request.getMethod())
            .addKeyValue("httpStatus", response.getStatus())
            .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
            .log("HTTP request completed");
      }
    } finally {
      if (previous == null) MDC.remove("traceId");
      else MDC.put("traceId", previous);
    }
  }
}
