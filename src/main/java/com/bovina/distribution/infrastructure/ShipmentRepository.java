package com.bovina.distribution.infrastructure;

import com.bovina.distribution.domain.Shipment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
  Optional<Shipment> findByOrganizationIdAndId(UUID organizationId, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from Shipment s where s.organizationId=:tenant and s.id=:id")
  Optional<Shipment> lock(@Param("tenant") UUID tenant, @Param("id") UUID id);
}
