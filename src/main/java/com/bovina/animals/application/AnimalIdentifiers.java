package com.bovina.animals.application;

import com.bovina.animals.domain.*;
import com.bovina.animals.infrastructure.*;
import com.bovina.audit.application.*;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnimalIdentifiers {
  private final TenantAccess access;
  private final AnimalRepository animals;
  private final AnimalIdentifierStore identifiers;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final Clock clock;
  private final StableIds ids;

  public AnimalIdentifiers(
      TenantAccess access,
      AnimalRepository animals,
      AnimalIdentifierStore identifiers,
      CommandReceipts receipts,
      AuditRecorder audit,
      Clock clock,
      StableIds ids) {
    this.access = access;
    this.animals = animals;
    this.identifiers = identifiers;
    this.receipts = receipts;
    this.audit = audit;
    this.clock = clock;
    this.ids = ids;
  }

  @Transactional
  public AnimalIdentifier add(ExecutionContext context, UUID key, UUID animal, Add input) {
    access.require(context, "master-data:write");
    var identifier =
        new AnimalIdentifier(
            input.id(),
            animal,
            input.type(),
            new IdentifierValue(input.value(), input.issuer()),
            input.validFrom(),
            input.validUntil(),
            AnimalIdentifier.Status.ACTIVE,
            0);
    return receipts.replayOrExecute(
        context,
        key,
        "ADD_ANIMAL_IDENTIFIER_V1",
        identifier,
        AnimalIdentifier.class,
        () -> {
          lock(context, animal).requireActive();
          identifiers.insert(context.tenantId(), identifier, context.actorId(), clock.instant());
          record(context, identifier, "REGISTER", null);
          return identifier;
        });
  }

  @Transactional
  public AnimalIdentifier retire(
      ExecutionContext context, UUID key, UUID animal, UUID id, Retire input) {
    access.require(context, "master-data:write");
    return receipts.replayOrExecute(
        context,
        key,
        "RETIRE_ANIMAL_IDENTIFIER_V1",
        new Retirement(animal, id, input),
        AnimalIdentifier.class,
        () -> {
          lock(context, animal);
          var identifier =
              identifiers
                  .find(context.tenantId(), animal, id)
                  .orElseThrow(
                      () ->
                          new ApplicationFailure(
                              ApplicationFailure.Kind.NOT_FOUND,
                              "IDENTIFIER_NOT_FOUND",
                              "Identifier not found"))
                  .retire(input.disposition(), input.expectedVersion(), input.reason());
          identifiers.retire(
              context.tenantId(), identifier, context.actorId(), clock.instant(), input.reason());
          record(context, identifier, "RETIRE", input.reason());
          return identifier;
        });
  }

  @Transactional(readOnly = true)
  public PageResult<AnimalIdentifier> history(
      ExecutionContext context, UUID animal, SearchPage page) {
    access.require(context, "master-data:read");
    animals
        .findByOrganizationIdAndId(context.tenantId(), animal)
        .orElseThrow(AnimalIdentifiers::missing);
    return new PageResult<>(
        identifiers.history(context.tenantId(), animal, page), page.page(), page.size());
  }

  private Animal lock(ExecutionContext c, UUID id) {
    return animals.lock(c.tenantId(), id).orElseThrow(AnimalIdentifiers::missing);
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "ANIMAL_NOT_FOUND", "Animal not found");
  }

  private void record(ExecutionContext c, AnimalIdentifier i, String action, String reason) {
    audit.record(
        new AuditEvent(
            ids.next(),
            c,
            clock.instant(),
            action,
            "ANIMAL_IDENTIFIER",
            i.id(),
            i.version(),
            reason,
            null,
            i.status().name()));
  }

  public record Add(
      UUID id,
      AnimalIdentifier.Type type,
      String value,
      String issuer,
      LocalDate validFrom,
      LocalDate validUntil) {}

  public record Retire(long expectedVersion, AnimalIdentifier.Status disposition, String reason) {}

  private record Retirement(UUID animal, UUID id, Retire input) {}
}
