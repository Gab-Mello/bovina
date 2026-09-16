package com.bovina.cryostorage.infrastructure;

import com.bovina.cryostorage.domain.EmbryoPackage;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmbryoPackageRepository extends JpaRepository<EmbryoPackage, UUID> {
  Optional<EmbryoPackage> findByOrganizationIdAndId(UUID organizationId, UUID id);

  Page<EmbryoPackage> findByOrganizationId(UUID organizationId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from EmbryoPackage p where p.organizationId = :tenant and p.id = :id")
  Optional<EmbryoPackage> lock(@Param("tenant") UUID tenant, @Param("id") UUID id);
}
