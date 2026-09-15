package com.bovina.embryology.application;

import com.bovina.embryology.domain.Embryo;
import com.bovina.embryology.infrastructure.EmbryoRepository;
import com.bovina.embryology.infrastructure.EmbryologyFacts;
import com.bovina.platform.application.ApplicationFailure;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbryoCryopreservationBoundary {
  private final EmbryoRepository embryos;
  private final EmbryologyFacts facts;

  public EmbryoCryopreservationBoundary(EmbryoRepository embryos, EmbryologyFacts facts) {
    this.embryos = embryos;
    this.facts = facts;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, Snapshot> lockForCryopreservation(UUID tenant, Collection<UUID> ids) {
    var ordered = new TreeSet<>(ids);
    if (ordered.isEmpty() || ordered.size() != ids.size() || ordered.size() > 100)
      throw rejected("INVALID_CRYOPRESERVATION_ITEMS");
    var locked = embryos.lockAll(tenant, ordered);
    if (locked.size() != ordered.size()) throw missing();
    var result = new HashMap<UUID, Snapshot>();
    for (var embryo : locked) {
      if (embryo.availability() != Embryo.AvailabilityStatus.AVAILABLE)
        throw conflict("EMBRYO_NOT_AVAILABLE_FOR_CRYOPRESERVATION");
      if (facts.hasActiveHold(tenant, embryo.id())) throw conflict("EMBRYO_ON_HOLD");
      var evaluation = facts.currentEvaluation(tenant, embryo.id());
      if (evaluation == null) throw conflict("EMBRYO_ASSESSMENT_REQUIRED_FOR_CRYOPRESERVATION");
      result.put(
          embryo.id(),
          new Snapshot(
              embryo.id(),
              embryo.matingId(),
              embryo.ownerId(),
              evaluation.id(),
              facts.developmentStageCode(tenant, evaluation.developmentStageCodeId()),
              embryo.identifiedAt(),
              evaluation.evaluatedAt(),
              embryo.version()));
    }
    return Map.copyOf(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<UUID, Member> lockPackageMembers(UUID tenant, Collection<UUID> ids) {
    var ordered = new TreeSet<>(ids);
    if (ordered.isEmpty() || ordered.size() != ids.size() || ordered.size() > 100)
      throw rejected("INVALID_PACKAGE_ITEMS");
    var locked = embryos.lockAll(tenant, ordered);
    if (locked.size() != ordered.size()) throw missing();
    var result = new HashMap<UUID, Member>();
    for (var embryo : locked) {
      if (embryo.availability() != Embryo.AvailabilityStatus.AVAILABLE)
        throw conflict("EMBRYO_NOT_AVAILABLE_FOR_PACKAGING");
      if (facts.hasActiveHold(tenant, embryo.id())) throw conflict("EMBRYO_ON_HOLD");
      result.put(embryo.id(), new Member(embryo.id(), embryo.matingId(), embryo.ownerId()));
    }
    return Map.copyOf(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void discardPackageMembers(UUID tenant, Collection<UUID> ids) {
    var ordered = new TreeSet<>(ids);
    var locked = embryos.lockAll(tenant, ordered);
    if (locked.size() != ordered.size()) throw missing();
    for (var embryo : locked) {
      if (facts.hasActiveHold(tenant, embryo.id())) throw conflict("EMBRYO_ON_HOLD");
      embryo.discard();
    }
    embryos.flush();
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "EMBRYO_NOT_FOUND", "Embryo not found");
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(ApplicationFailure.Kind.REJECTED, code, "Invalid embryo items");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, code, "Embryo is not eligible");
  }

  public record Snapshot(
      UUID embryoId,
      UUID matingId,
      UUID ownerId,
      UUID evaluationId,
      String stageCode,
      java.time.Instant identifiedAt,
      java.time.Instant evaluatedAt,
      long embryoVersion) {}

  public record Member(UUID embryoId, UUID matingId, UUID ownerId) {}
}
