package com.bovina.opu.infrastructure;

import com.bovina.opu.domain.OocyteCollection;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface OocyteCollectionRepository extends Repository<OocyteCollection, UUID> {
  void flush();

  Optional<OocyteCollection> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from OocyteCollection c where c.organizationId=:tenant and c.id=:id")
  Optional<OocyteCollection> lock(UUID tenant, UUID id);

  @Query(
      "select c from OocyteCollection c where c.organizationId=:tenant and c.opuSessionId=:session order by c.id")
  List<OocyteCollection> page(UUID tenant, UUID session, Pageable page);
}
