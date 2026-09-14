package com.bovina.semen.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SemenProvenanceTest {
  private final StableIds ids = new StableIds();

  @Test
  void normalizesOpenCodesWithoutInventingAClosedTaxonomy() {
    var producer =
        new ExternalEstablishmentReference(
            ids.next(),
            null,
            " Producer ",
            "semen_center",
            null,
            null,
            "br",
            "documented",
            null,
            null,
            0);
    var batch =
        new SemenBatch(
            ids.next(),
            " Lot 24 ",
            ids.next(),
            producer.id(),
            "supplier_document",
            "documented",
            "conventional",
            null,
            null,
            null,
            0,
            DataProvenance.manual(ids.next(), Instant.EPOCH));

    assertThat(producer.establishmentType()).isEqualTo("SEMEN_CENTER");
    assertThat(producer.country()).isEqualTo("BR");
    assertThat(batch.provenanceCode()).isEqualTo("SUPPLIER_DOCUMENT");
    assertThat(batch.semenType()).isEqualTo("CONVENTIONAL");
  }

  @Test
  void rejectsMalformedProvenanceIdentity() {
    assertThatThrownBy(
            () ->
                new SemenBatch(
                    ids.next(),
                    "batch",
                    ids.next(),
                    ids.next(),
                    "not valid",
                    "DOCUMENTED",
                    null,
                    null,
                    null,
                    "ACTIVE",
                    0,
                    DataProvenance.manual(ids.next(), Instant.EPOCH)))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_SEMEN_BATCH");
  }
}
