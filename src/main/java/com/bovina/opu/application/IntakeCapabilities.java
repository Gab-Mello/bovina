package com.bovina.opu.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bovina.opu.intake")
public record IntakeCapabilities(boolean transportEnabled, boolean externalReceiptEnabled) {}
