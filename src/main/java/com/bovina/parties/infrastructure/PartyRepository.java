package com.bovina.parties.infrastructure;

import com.bovina.parties.domain.Party;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface PartyRepository extends Repository<Party, UUID> {
  Party save(Party party);

  void flush();

  Optional<Party> findByOrganizationIdAndId(UUID organizationId, UUID id);
}
