package com.bovina.embryology.application;

import com.bovina.embryology.infrastructure.*;
import com.bovina.platform.application.ApplicationFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Owns the Embryo lock and availability transitions used by transfer commands. */
@Service
public class EmbryoTransferBoundary {
  private final EmbryoRepository embryos;
  private final EmbryologyFacts facts;

  public EmbryoTransferBoundary(EmbryoRepository embryos, EmbryologyFacts facts) {
    this.embryos = embryos;
    this.facts = facts;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, State> reserveFresh(UUID tenant, Collection<Expected> requested) {
    return mutate(tenant, requested, Action.RESERVE);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, State> releaseFresh(UUID tenant, Collection<Expected> requested) {
    return mutate(tenant, requested, Action.RELEASE);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, State> performFresh(UUID tenant, Collection<Expected> requested) {
    return mutate(tenant, requested, Action.PERFORM);
  }

  private Map<UUID, State> mutate(UUID tenant, Collection<Expected> requested, Action action) {
    if (requested.isEmpty() || requested.size() > 100)
      throw rejected("INVALID_TRANSFER_BATCH_SIZE", "Transfer batch must contain 1 to 100 items");
    var expected = new HashMap<UUID, Long>();
    for (var item : requested)
      if (expected.put(item.embryoId(), item.version()) != null)
        throw rejected("DUPLICATE_EMBRYO", "An embryo appears more than once in the command");
    var orderedIds = new TreeSet<>(expected.keySet());
    var locked = embryos.lockAll(tenant, orderedIds);
    if (locked.size() != orderedIds.size()) throw missing();
    var held = facts.embryosWithActiveHold(tenant, orderedIds);
    var result = new HashMap<UUID, State>();
    for (var embryo : locked) {
      long version = expected.get(embryo.id());
      if (embryo.version() != version)
        throw new ApplicationFailure(
            ApplicationFailure.Kind.CONFLICT, "STALE_EMBRYO_VERSION", "Embryo version has changed");
      if (held.contains(embryo.id()))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.CONFLICT,
            "EMBRYO_ON_HOLD",
            "Release active holds before transferring the embryo");
      switch (action) {
        case RESERVE -> embryo.reserve();
        case RELEASE -> embryo.releaseReservation();
        case PERFORM -> embryo.performTransfer();
      }
      result.put(
          embryo.id(),
          new State(
              embryo.id(),
              embryo.matingId(),
              embryo.identifiedAt(),
              embryo.availability().name(),
              version + 1));
    }
    embryos.flush();
    return Map.copyOf(result);
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "EMBRYO_NOT_FOUND", "Embryo not found");
  }

  private static ApplicationFailure rejected(String code, String message) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, message);
  }

  private enum Action {
    RESERVE,
    RELEASE,
    PERFORM
  }

  public record Expected(UUID embryoId, long version) {}

  public record State(
      UUID embryoId, UUID matingId, Instant identifiedAt, String availability, long version) {}
}
