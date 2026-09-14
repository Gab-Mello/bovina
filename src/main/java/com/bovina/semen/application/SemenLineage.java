package com.bovina.semen.application;

import com.bovina.animals.application.SireDirectory.Sire;
import com.bovina.semen.domain.*;

public record SemenLineage(SemenBatch batch, Sire sire, ExternalEstablishmentReference producer) {}
