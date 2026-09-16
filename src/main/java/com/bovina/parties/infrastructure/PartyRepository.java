package com.bovina.parties.infrastructure;

import com.bovina.parties.domain.Party;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PartyRepository extends Repository<Party, UUID> {
  Party save(Party party);

  void flush();

  Optional<Party> findByOrganizationIdAndId(UUID organizationId, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Party p where p.organizationId=:organizationId and p.id=:id")
  Optional<Party> lock(UUID organizationId, UUID id);
}
