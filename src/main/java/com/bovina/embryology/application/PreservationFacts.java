package com.bovina.embryology.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read boundary for preservation facts; fresh-transfer behavior does not own cryostorage tables.
 */
public interface PreservationFacts {
  Set<UUID> cryopreservedEmbryos(UUID tenant, Collection<UUID> embryoIds);

  ThawEvidence thawEvidence(UUID tenant, UUID thawEventId, UUID embryoId);

  State state(UUID tenant, UUID embryoId);

  Map<UUID, State> states(UUID tenant, Collection<UUID> embryoIds);

  record State(String preservation, UUID currentLocationId) {}

  record ThawEvidence(Instant occurredAt) {}
}
