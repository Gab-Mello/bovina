package com.bovina.animals.application;

import com.bovina.animals.domain.Breed;
import com.bovina.animals.infrastructure.BreedStore;
import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Breeds {
  private final TenantAccess access;
  private final BreedStore breeds;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public Breeds(
      TenantAccess access,
      BreedStore breeds,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.breeds = breeds;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public Breed register(ExecutionContext c, UUID key, Register input) {
    access.require(c, "master-data:write");
    var breed = new Breed(input.id(), input.name(), input.code(), "ACTIVE", 0);
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_BREED_V1",
        input,
        Breed.class,
        () -> {
          breeds.insert(c.tenantId(), breed, c.actorId(), clock.instant());
          record(c, breed, "REGISTER");
          return breed;
        });
  }

  @Transactional(readOnly = true)
  public Breed get(ExecutionContext c, UUID id) {
    access.require(c, "master-data:read");
    return find(c, id);
  }

  @Transactional(readOnly = true)
  public PageResult<Breed> search(ExecutionContext c, SearchPage page) {
    access.require(c, "master-data:read");
    return new PageResult<>(breeds.search(c.tenantId(), page), page.page(), page.size());
  }

  @Transactional
  public Breed deactivate(ExecutionContext c, UUID key, UUID id, long expected) {
    access.require(c, "master-data:write");
    return receipts.replayOrExecute(
        c,
        key,
        "DEACTIVATE_BREED_V1",
        new Deactivate(id, expected),
        Breed.class,
        () -> {
          var breed = find(c, id).deactivate(expected);
          breeds.deactivate(c.tenantId(), breed);
          record(c, breed, "DEACTIVATE");
          return breed;
        });
  }

  private Breed find(ExecutionContext c, UUID id) {
    return breeds
        .find(c.tenantId(), id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND, "BREED_NOT_FOUND", "Breed not found"));
  }

  private void record(ExecutionContext c, Breed b, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "BREED",
            b.id(),
            b.version(),
            null,
            null,
            b.status()));
  }

  public record Register(UUID id, String name, String code) {}

  private record Deactivate(UUID id, long expectedVersion) {}
}
