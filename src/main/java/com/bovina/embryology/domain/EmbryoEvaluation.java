package com.bovina.embryology.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public record EmbryoEvaluation(
    UUID id,
    UUID embryoId,
    UUID schemeVersionId,
    UUID developmentStageCodeId,
    UUID qualityGradeCodeId,
    Instant evaluatedAt,
    UUID evaluatorProfessionalId,
    String notes,
    UUID supersedesEvaluationId,
    DataProvenance provenance) {
  public EmbryoEvaluation {
    StableIds.requireVersion7(id);
    StableIds.requireVersion7(embryoId);
    StableIds.requireVersion7(schemeVersionId);
    StableIds.requireVersion7(developmentStageCodeId);
    StableIds.requireVersion7(qualityGradeCodeId);
    if (evaluatedAt == null || provenance == null || (notes != null && notes.length() > 2000))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_EMBRYO_EVALUATION",
          "Evaluation fields are invalid");
    evaluatedAt = evaluatedAt.truncatedTo(ChronoUnit.MICROS);
  }
}
