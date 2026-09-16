package com.bovina.transfer.infrastructure;

import com.bovina.transfer.domain.RecipientCycle;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface RecipientCycleRepository extends Repository<RecipientCycle, UUID> {
  RecipientCycle save(RecipientCycle cycle);

  void flush();

  Optional<RecipientCycle> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RecipientCycle c where c.organizationId=:tenant and c.id=:id")
  Optional<RecipientCycle> lock(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select c from RecipientCycle c where c.organizationId=:tenant and c.id in :ids order by c.id")
  List<RecipientCycle> lockAll(UUID tenant, Collection<UUID> ids);

  @Query(
      "select c from RecipientCycle c where c.organizationId=:tenant and (:recipient is null or c.recipientAnimalId=:recipient) and (:status is null or c.status=:status) order by c.openedOn desc,c.id")
  List<RecipientCycle> page(
      UUID tenant, UUID recipient, RecipientCycle.Status status, Pageable pageable);
}
