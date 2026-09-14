package com.bovina.opu.application;

import com.bovina.opu.domain.ExternalOocyteReceipt;
import com.bovina.parties.application.FarmOrigin;
import com.bovina.platform.domain.DataProvenance;

public record ExternalReceiptView(
    ExternalOocyteReceipt receipt,
    String status,
    FarmOrigin farmSnapshot,
    DataProvenance provenance) {}
