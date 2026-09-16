package com.bovina.distribution.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** False records operational dispatches with UNKNOWN readiness; it does not certify compliance. */
@ConfigurationProperties("bovina.distribution")
public record DistributionSettings(Boolean regulatedDispatch) {
  public DistributionSettings {
    if (regulatedDispatch == null) regulatedDispatch = true;
  }
}
