package com.bovina.opu.infrastructure;

import com.bovina.opu.domain.OpuSession;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface OpuSessionRepository extends Repository<OpuSession, UUID> {
  OpuSession save(OpuSession session);

  void flush();

  Optional<OpuSession> findByOrganizationIdAndId(UUID tenant, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from OpuSession s where s.organizationId=:tenant and s.id=:id")
  Optional<OpuSession> lock(UUID tenant, UUID id);

  @Query(
      "select s from OpuSession s where s.organizationId=:tenant order by s.performedAt desc,s.id")
  List<OpuSession> page(UUID tenant, Pageable page);
}
