package com.bovina.audit.application;

import com.bovina.audit.infrastructure.AuditStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditRecorder {
  private final AuditStore store;
  private final MeterRegistry meters;

  public AuditRecorder(AuditStore store, MeterRegistry meters) {
    this.store = store;
    this.meters = meters;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(AuditEvent event) {
    try {
      store.append(event);
    } catch (RuntimeException failure) {
      meters.counter("bovina.audit.failures").increment();
      throw failure;
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAll(List<AuditEvent> events) {
    try {
      store.appendAll(List.copyOf(events));
    } catch (RuntimeException failure) {
      meters.counter("bovina.audit.failures").increment();
      throw failure;
    }
  }
}
