package com.bovina.documents.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import org.junit.jupiter.api.Test;

class DocumentReferenceTest {
  @Test
  void requiresARevisionAndRejectsInvalidDigestOrSelfSupersession() {
    var id = new StableIds().next();
    assertThatThrownBy(() -> new DocumentReference(id, "ART", "reference", "", null, null))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new DocumentReference(id, "ART", "reference", "1", "bad-hash", null))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> new DocumentReference(id, "ART", "reference", "1", null, id))
        .isInstanceOf(ApplicationFailure.class);
  }
}
