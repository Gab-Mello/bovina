package com.bovina.animals.application;

import com.bovina.animals.domain.*;
import com.bovina.animals.infrastructure.*;
import com.bovina.audit.application.*;
import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.application.CounterpartyAccess;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.EffectivePeriod;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnimalOwnership {
  private final TenantAccess access;
  private final AnimalRepository animals;
  private final OwnershipStore ownership;
  private final CounterpartyAccess counterparties;
  private final DocumentReferences documents;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public AnimalOwnership(
      TenantAccess access,
      AnimalRepository animals,
      OwnershipStore ownership,
      CounterpartyAccess counterparties,
      DocumentReferences documents,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.animals = animals;
    this.ownership = ownership;
    this.counterparties = counterparties;
    this.documents = documents;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public AnimalOwnershipAssignment assign(ExecutionContext c, UUID key, UUID animal, Assign input) {
    access.require(c, "master-data:write");
    var assignment =
        new AnimalOwnershipAssignment(
            input.id(), animal, input.ownerId(), input.period(), input.sourceDocumentId(), 0);
    return receipts.replayOrExecute(
        c,
        key,
        "ASSIGN_ANIMAL_OWNER_V1",
        assignment,
        AnimalOwnershipAssignment.class,
        () -> {
          // Every assignment writer locks the animal, including end commands. Different owners may
          // coexist.
          lock(c, animal).requireActive();
          counterparties.requireAnimalOwner(c.tenantId(), input.ownerId());
          if (input.sourceDocumentId() != null)
            documents.requireReference(c.tenantId(), input.sourceDocumentId());
          if (ownership.overlaps(c.tenantId(), animal, input.ownerId(), input.period()))
            throw new ApplicationFailure(
                ApplicationFailure.Kind.CONFLICT,
                "OWNERSHIP_OVERLAP",
                "This owner already has an assignment in that period");
          ownership.insert(c.tenantId(), assignment, c.actorId(), clock.instant());
          record(c, assignment, "ASSIGN");
          return assignment;
        });
  }

  @Transactional
  public AnimalOwnershipAssignment end(
      ExecutionContext c, UUID key, UUID animal, UUID id, End input) {
    access.require(c, "master-data:write");
    return receipts.replayOrExecute(
        c,
        key,
        "END_ANIMAL_OWNERSHIP_V1",
        new EndIntent(animal, id, input),
        AnimalOwnershipAssignment.class,
        () -> {
          lock(c, animal);
          var assignment =
              ownership
                  .find(c.tenantId(), animal, id)
                  .orElseThrow(
                      () ->
                          new ApplicationFailure(
                              ApplicationFailure.Kind.NOT_FOUND,
                              "OWNERSHIP_NOT_FOUND",
                              "Ownership assignment not found"))
                  .end(input.until(), input.expectedVersion());
          ownership.end(c.tenantId(), assignment);
          record(c, assignment, "END_ASSIGNMENT");
          return assignment;
        });
  }

  @Transactional(readOnly = true)
  public PageResult<AnimalOwnershipAssignment> history(
      ExecutionContext c, UUID animal, SearchPage page) {
    access.require(c, "master-data:read");
    animals.findByOrganizationIdAndId(c.tenantId(), animal).orElseThrow(AnimalOwnership::missing);
    return new PageResult<>(
        ownership.history(c.tenantId(), animal, page), page.page(), page.size());
  }

  private Animal lock(ExecutionContext c, UUID id) {
    return animals.lock(c.tenantId(), id).orElseThrow(AnimalOwnership::missing);
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "ANIMAL_NOT_FOUND", "Animal not found");
  }

  private void record(ExecutionContext c, AnimalOwnershipAssignment a, String action) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "ANIMAL_OWNERSHIP",
            a.id(),
            a.version(),
            null,
            null,
            a.period().until() == null ? "OPEN" : "BOUNDED"));
  }

  public record Assign(UUID id, UUID ownerId, EffectivePeriod period, UUID sourceDocumentId) {}

  public record End(long expectedVersion, LocalDate until) {}

  private record EndIntent(UUID animal, UUID id, End input) {}
}
