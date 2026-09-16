package com.bovina.parties.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.parties.domain.ClientImportBatch.*;
import com.bovina.platform.application.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClientImportBatchTest {
  private final StableIds ids = new StableIds();

  @Test
  void validatesEveryRowAndRejectsDuplicateClientIdentities() {
    var id = ids.next();
    var batch =
        new ClientImportBatch(
            ids.next(),
            Mode.ATOMIC,
            null,
            List.of(row(id, "First"), row(id, "Second"), row(ids.next(), " ")));
    assertThat(batch.validate(Set.of()))
        .extracting(ItemResult::errorCode)
        .containsExactly(
            "DUPLICATE_CLIENT_IN_BATCH", "DUPLICATE_CLIENT_IN_BATCH", "INVALID_CLIENT_DETAILS");
  }

  @Test
  void rejectsAmbiguousItemKeysAndUnboundedBatches() {
    var row = row(ids.next(), "Client");
    assertThatThrownBy(
            () -> new ClientImportBatch(ids.next(), Mode.PARTIAL, null, List.of(row, row)))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () ->
                new ClientImportBatch(ids.next(), Mode.ATOMIC, null, Collections.nCopies(101, row)))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void defensivelyCopiesInputsAndReportsExistingIdentitiesWithoutMutatingThem() {
    var row = row(ids.next(), "Client");
    var rows = new ArrayList<>(List.of(row));
    var batch = new ClientImportBatch(ids.next(), Mode.PARTIAL, null, rows);
    assertThatThrownBy(() -> batch.requireMode(Mode.ATOMIC)).isInstanceOf(ApplicationFailure.class);
    rows.clear();
    assertThat(batch.validate(Set.of(row.id())))
        .extracting(ItemResult::errorCode)
        .containsExactly("CLIENT_ID_ALREADY_EXISTS");
    assertThatThrownBy(() -> batch.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private Row row(UUID id, String name) {
    return new Row(ids.next(), id, ClientType.PERSON, name, Instant.EPOCH);
  }
}
