package com.bovina.animals.infrastructure;

import com.bovina.animals.domain.Animal;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface AnimalRepository extends Repository<Animal, UUID> {
  Animal save(Animal animal);

  void flush();

  Optional<Animal> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Animal a where a.organizationId=:tenant and a.id=:id")
  Optional<Animal> lock(UUID tenant, UUID id);

  @Query(
      value =
          """
      SELECT a.* FROM animal a WHERE a.organization_id=:tenant
      AND (coalesce(a.name,'') ILIKE :pattern OR EXISTS (SELECT 1 FROM animal_identifier i
          WHERE i.organization_id=a.organization_id AND i.animal_id=a.id AND i.status='ACTIVE' AND i.normalized_value ILIKE :pattern))
      AND (CAST(:owner AS uuid) IS NULL OR EXISTS (SELECT 1 FROM animal_ownership_assignment o
          WHERE o.organization_id=a.organization_id AND o.animal_id=a.id AND o.owner_id=:owner
          AND (CAST(:ownedOn AS date) IS NULL OR (o.valid_from<=:ownedOn AND (o.valid_until IS NULL OR o.valid_until>:ownedOn)))))
      ORDER BY lower(a.name) NULLS LAST,a.id
      """,
      nativeQuery = true)
  List<Animal> search(UUID tenant, String pattern, UUID owner, LocalDate ownedOn, Pageable page);
}
