package com.bovina.cryostorage.infrastructure;

import com.bovina.cryostorage.domain.EmbryoPackage;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmbryoPackageRepository extends JpaRepository<EmbryoPackage, UUID> {
  Optional<EmbryoPackage> findByOrganizationIdAndId(UUID organizationId, UUID id);

  @Query(
      "select p from EmbryoPackage p where p.organizationId=:tenant and lower(p.packageCode) like lower(:pattern) escape '\\' and (:location is null or p.currentLocationId=:location) and (:owner is null or p.ownerPartyId=:owner) order by p.id")
  List<EmbryoPackage> search(
      UUID tenant, String pattern, UUID location, UUID owner, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from EmbryoPackage p where p.organizationId = :tenant and p.id = :id")
  Optional<EmbryoPackage> lock(@Param("tenant") UUID tenant, @Param("id") UUID id);
}
