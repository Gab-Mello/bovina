package com.bovina.operations.infrastructure;

import com.bovina.operations.domain.Establishment;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface EstablishmentRepository extends Repository<Establishment, UUID> {
  Establishment save(Establishment establishment);

  void flush();

  Optional<Establishment> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Establishment e where e.organizationId=:tenant and e.id=:id")
  Optional<Establishment> lock(UUID tenant, UUID id);

  @Query(
      "select e from Establishment e where e.organizationId=:tenant and lower(e.legalDisplayName) like lower(:pattern) escape '\\' order by lower(e.legalDisplayName),e.id")
  List<Establishment> search(UUID tenant, String pattern, Pageable page);
}
