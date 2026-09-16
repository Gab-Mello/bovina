package com.bovina.embryology.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.embryology.domain.AssessmentCatalog.*;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbryologyInvariantTest {
  private final StableIds ids = new StableIds();

  @Test
  void availabilityTransitionsAreExplicitAndDoNotRepresentOtherDimensions() {
    var embryo = embryo();
    embryo.reserve();
    assertThat(embryo.availability()).isEqualTo(Embryo.AvailabilityStatus.RESERVED);
    embryo.releaseReservation();
    embryo.discard();
    assertThat(embryo.availability()).isEqualTo(Embryo.AvailabilityStatus.DISCARDED);
    assertThatThrownBy(embryo::reserve)
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("EMBRYO_NOT_AVAILABLE");
  }

  @Test
  void publishedAssessmentVersionUsesVersionOwnedOpenCodes() {
    var versionId = ids.next();
    var stage = new Code(ids.next(), versionId, Dimension.DEVELOPMENT_STAGE, "morula", "Morula", 1);
    var grade = new Code(ids.next(), versionId, Dimension.QUALITY_GRADE, "a", "Grade A", 1);
    var version =
        new Version(
            versionId,
            ids.next(),
            "2026.1",
            LocalDate.of(2026, 1, 1),
            "PUBLISHED",
            ids.next(),
            Instant.EPOCH,
            List.of(stage, grade));
    assertThat(version.codes()).extracting(Code::code).containsExactly("MORULA", "A");
  }

  @Test
  void evaluationRequiresTypedStableReferences() {
    assertThatThrownBy(
            () ->
                new EmbryoEvaluation(
                    ids.next(),
                    ids.next(),
                    ids.next(),
                    null,
                    ids.next(),
                    Instant.EPOCH,
                    null,
                    null,
                    null,
                    DataProvenance.manual(ids.next(), Instant.EPOCH)))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_STABLE_ID");
  }

  private Embryo embryo() {
    return new Embryo(
        ids.next(),
        new Embryo.Registration(ids.next(), ids.next(), "E-1", null, Instant.EPOCH),
        DataProvenance.manual(ids.next(), Instant.EPOCH));
  }
}
