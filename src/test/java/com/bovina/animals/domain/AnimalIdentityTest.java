package com.bovina.animals.domain;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class AnimalIdentityTest {
  private final StableIds ids = new StableIds();

  @Test
  void oneFemaleIdentitySupportsDonorAndRecipientWithoutSeparateAnimals() {
    var animal =
        new Animal(
            ids.next(),
            new Animal.Registration(ids.next(), Animal.Sex.FEMALE, "Female", null, null),
            DataProvenance.manual(ids.next(), Instant.EPOCH));
    animal.requireSexFor(Animal.Role.DONOR);
    animal.requireSexFor(Animal.Role.RECIPIENT);
    assertThatThrownBy(() -> animal.requireSexFor(Animal.Role.SIRE))
        .isInstanceOf(ApplicationFailure.class);
    var male =
        new Animal(
            ids.next(),
            new Animal.Registration(ids.next(), Animal.Sex.MALE, null, null, null),
            DataProvenance.manual(ids.next(), Instant.EPOCH));
    male.requireSexFor(Animal.Role.SIRE);
    assertThatThrownBy(() -> male.requireSexFor(Animal.Role.DONOR))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void normalizationPreservesSignificantCharactersAndTreatsBlankIssuerAsUnknown() {
    var value = new IdentifierValue(" 00-a/9 ", " association ");
    assertThat(value.value()).isEqualTo("00-a/9");
    assertThat(value.normalized()).isEqualTo("00-A/9");
    assertThat(value.issuer()).isEqualTo("ASSOCIATION");
    assertThat(new IdentifierValue("e\u0301", null).normalized())
        .isEqualTo(new IdentifierValue("é", " ").normalized());
    assertThat(new IdentifierValue("0 01", null).normalized())
        .isNotEqualTo(new IdentifierValue("001", null).normalized());
    assertThatThrownBy(() -> new IdentifierValue(" ", null)).isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void identifierRetirementRequiresReasonAndPreservesOriginalEvidence() {
    var identifier =
        new AnimalIdentifier(
            ids.next(),
            ids.next(),
            AnimalIdentifier.Type.RGD,
            new IdentifierValue("0001", null),
            null,
            null,
            AnimalIdentifier.Status.ACTIVE,
            0);
    var retired = identifier.retire(AnimalIdentifier.Status.CORRECTED, 0, "Incorrect source");
    assertThat(retired.identifier()).isEqualTo(identifier.identifier());
    assertThatThrownBy(() -> identifier.retire(AnimalIdentifier.Status.REVOKED, 0, " "))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> retired.retire(AnimalIdentifier.Status.REVOKED, 1, "Again"))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void ownershipEndIsHalfOpenAndCannotRewriteAnAlreadyBoundedAssignment() {
    var start = LocalDate.of(2026, 1, 1);
    var assignment =
        new AnimalOwnershipAssignment(
            ids.next(), ids.next(), ids.next(), new EffectivePeriod(start, null), null, 0);
    var ended = assignment.end(start.plusMonths(1), 0);
    assertThat(ended.ownerId()).isEqualTo(assignment.ownerId());
    assertThatThrownBy(() -> assignment.end(start, 0)).isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> ended.end(start.plusMonths(2), 1))
        .isInstanceOf(ApplicationFailure.class);
  }
}
