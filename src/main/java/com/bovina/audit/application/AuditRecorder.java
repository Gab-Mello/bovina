package com.bovina.audit.application;

import com.bovina.audit.infrastructure.AuditStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditRecorder {
  private final AuditStore store;

  public AuditRecorder(AuditStore store) {
    this.store = store;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(AuditEvent event) {
    store.append(event);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAll(List<AuditEvent> events) {
    store.appendAll(List.copyOf(events));
  }
}
