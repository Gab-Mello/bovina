package com.bovina.animals.application;

import com.bovina.animals.domain.*;
import com.bovina.animals.infrastructure.*;
import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Animals {
  private final TenantAccess access;
  private final AnimalRepository animals;
  private final BreedStore breeds;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public Animals(
      TenantAccess access,
      AnimalRepository animals,
      BreedStore breeds,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.animals = animals;
    this.breeds = breeds;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public View register(ExecutionContext context, UUID key, Animal.Registration input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "REGISTER_ANIMAL_V1",
        input,
        View.class,
        () -> {
          if (input.breedId() != null) {
            var breed =
                breeds
                    .lock(context.tenantId(), input.breedId())
                    .orElseThrow(
                        () ->
                            new ApplicationFailure(
                                ApplicationFailure.Kind.NOT_FOUND,
                                "BREED_NOT_FOUND",
                                "Breed not found"));
            if (!breed.status().equals("ACTIVE"))
              throw new ApplicationFailure(
                  ApplicationFailure.Kind.CONFLICT, "BREED_INACTIVE", "Breed is inactive");
          }
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          var animal =
              animals.save(
                  new Animal(
                      context.tenantId(), input, DataProvenance.manual(context.actorId(), now)));
          animals.flush();
          record(context, animal, "REGISTER");
          return view(animal);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext context, UUID id) {
    access.require(context, "master-data:read");
    return view(find(context, id));
  }

  @Transactional(readOnly = true)
  public PageResult<View> search(
      ExecutionContext context, SearchPage page, UUID owner, LocalDate ownedOn) {
    access.require(context, "master-data:read");
    return new PageResult<>(
        animals
            .search(
                context.tenantId(),
                page.pattern(),
                owner,
                ownedOn,
                PageRequest.of(page.page(), page.size()))
            .stream()
            .map(Animals::view)
            .toList(),
        page.page(),
        page.size());
  }

  @Transactional
  public View archive(ExecutionContext context, UUID key, UUID id, long expected) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "ARCHIVE_ANIMAL_V1",
        new Archive(id, expected),
        View.class,
        () -> {
          var animal = find(context, id);
          animal.archive(expected);
          animals.flush();
          record(context, animal, "ARCHIVE");
          return view(animal);
        });
  }

  private Animal find(ExecutionContext c, UUID id) {
    return animals
        .findByOrganizationIdAndId(c.tenantId(), id)
        .orElseThrow(
            () ->
                new ApplicationFailure(
                    ApplicationFailure.Kind.NOT_FOUND, "ANIMAL_NOT_FOUND", "Animal not found"));
  }

  private void record(ExecutionContext c, Animal a, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "ANIMAL",
            a.id(),
            a.version(),
            null,
            null,
            a.status().name()));
  }

  private static View view(Animal a) {
    return new View(
        a.registration(),
        a.status(),
        a.version(),
        a.provenance().originType().name(),
        a.provenance().recordedByUserId(),
        a.provenance().recordedAt());
  }

  public record View(
      Animal.Registration registration,
      Animal.Status status,
      long version,
      String originType,
      UUID recordedBy,
      Instant recordedAt) {}

  private record Archive(UUID id, long expectedVersion) {}
}
