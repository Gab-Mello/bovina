package com.bovina.parties.infrastructure;

import com.bovina.parties.domain.FarmProperty;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface FarmPropertyRepository extends Repository<FarmProperty, UUID> {
  FarmProperty save(FarmProperty property);

  void flush();

  Optional<FarmProperty> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Query(
      "select p from FarmProperty p where p.organizationId=:tenant and lower(p.name) like lower(:pattern) escape '\\' order by lower(p.name),p.id")
  List<FarmProperty> search(UUID tenant, String pattern, Pageable page);
}
