package com.bovina.fertilization.application;

import com.bovina.semen.application.SemenLineage;
import java.util.UUID;

public record MatingLineageSnapshot(UUID collectionId, UUID donorId, SemenLineage semen) {}
