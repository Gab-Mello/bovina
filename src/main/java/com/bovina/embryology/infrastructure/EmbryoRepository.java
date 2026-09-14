package com.bovina.embryology.infrastructure;

import com.bovina.embryology.domain.Embryo;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface EmbryoRepository extends Repository<Embryo, UUID> {
  void saveAll(Iterable<Embryo> embryos);

  void flush();

  Optional<Embryo> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Embryo e where e.organizationId=:tenant and e.id=:id")
  Optional<Embryo> lock(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Embryo e where e.organizationId=:tenant and e.id in :ids order by e.id")
  List<Embryo> lockAll(UUID tenant, Collection<UUID> ids);

  @Query(
      "select e from Embryo e where e.organizationId=:tenant and e.matingId=:mating order by e.id")
  List<Embryo> page(UUID tenant, UUID mating, Pageable pageable);
}
