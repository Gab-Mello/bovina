package com.bovina.opu.application;

import com.bovina.opu.domain.TransportReceipt;
import com.bovina.platform.domain.DataProvenance;

public record TransportView(TransportReceipt observation, DataProvenance provenance) {}
