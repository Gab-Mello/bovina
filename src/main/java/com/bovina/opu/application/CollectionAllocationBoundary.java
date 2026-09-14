package com.bovina.opu.application;

import com.bovina.identity.application.TenantAccess;
import com.bovina.opu.domain.OocyteCounts;
import com.bovina.opu.infrastructure.OocyteCollectionRepository;
import com.bovina.platform.application.ExecutionContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CollectionAllocationBoundary {
  private final TenantAccess access;
  private final OocyteCollectionRepository collections;

  public CollectionAllocationBoundary(TenantAccess access, OocyteCollectionRepository collections) {
    this.access = access;
    this.collections = collections;
  }

  /**
   * Caller must sum committed allocation facts after this lock and persist consumption in the same
   * transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Capacity lockCompleted(ExecutionContext context, UUID collectionId) {
    access.require(context, "opu:write");
    var c =
        collections.lock(context.tenantId(), collectionId).orElseThrow(OocyteCollections::missing);
    c.requireAllocatable();
    return new Capacity(c.id(), c.sessionId(), c.donorId(), c.version(), c.counts().viable());
  }

  public record Capacity(
      UUID collectionId, UUID sessionId, UUID donorId, long version, int viable) {
    public long availableAfter(long confirmedAllocation) {
      return new OocyteCounts(viable, viable, null).availableAfter(confirmedAllocation);
    }

    public void requireAllocation(long confirmedAllocation, int requested) {
      new OocyteCounts(viable, viable, null).requireAllocation(confirmedAllocation, requested);
    }
  }
}
