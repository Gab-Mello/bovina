package com.bovina.audit.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bovina.audit.infrastructure.AuditStore;
import com.bovina.platform.application.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AuditMetricsTest {
  @Test
  void auditFailureIsCountedAndPropagatedInsteadOfAllowingUnauditedCommit() {
    var store = mock(AuditStore.class);
    var meters = new SimpleMeterRegistry();
    var ids = new StableIds();
    var context = new ExecutionContext(ids.next(), ids.next(), Set.of(), ids.next());
    var event =
        new AuditEvent(
            ids.next(),
            context,
            Instant.EPOCH,
            "CREATE",
            "EMBRYO",
            ids.next(),
            0L,
            null,
            null,
            "AVAILABLE");
    var failure = new DataAccessResourceFailureException("Database unavailable");
    doThrow(failure).when(store).append(event);
    doThrow(failure).when(store).appendAll(List.of(event));
    var audit = new AuditRecorder(store, meters);

    assertThatThrownBy(() -> audit.record(event)).isSameAs(failure);
    assertThatThrownBy(() -> audit.recordAll(List.of(event))).isSameAs(failure);
    assertThat(meters.get("bovina.audit.failures").counter().count()).isEqualTo(2);
    assertThat(meters.get("bovina.audit.failures").counter().getId().getTags()).isEmpty();
  }
}
