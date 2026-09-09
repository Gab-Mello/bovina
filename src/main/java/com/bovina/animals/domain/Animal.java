package com.bovina.animals.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "animal")
public class Animal {
  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 16)
  private String species;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Sex sex;

  @Column(length = 200)
  private String name;

  private UUID breedId;
  private LocalDate birthDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Embedded private DataProvenance provenance;
  @Version private Long version;

  protected Animal() {}

  public Animal(UUID tenant, Registration input, DataProvenance provenance) {
    id = input.id();
    organizationId = Objects.requireNonNull(tenant);
    species = "BOVINE";
    sex = input.sex();
    name = input.name();
    breedId = input.breedId();
    birthDate = input.birthDate();
    status = Status.ACTIVE;
    this.provenance = Objects.requireNonNull(provenance);
  }

  public void requireActive() {
    if (status != Status.ACTIVE)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "ANIMAL_NOT_ACTIVE", "Animal is not active");
  }

  public void requireSexFor(Role role) {
    boolean compatible =
        switch (Objects.requireNonNull(role)) {
          case SIRE -> sex == Sex.MALE;
          case DONOR, RECIPIENT -> sex == Sex.FEMALE;
        };
    if (!compatible)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "ANIMAL_SEX_INCOMPATIBLE",
          "Animal sex is incompatible with this role");
  }

  public void archive(long expected) {
    if (version == null || version != expected)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "CONCURRENT_WRITE_CONFLICT", "Animal has changed");
    status = Status.ARCHIVED;
  }

  public UUID id() {
    return id;
  }

  public Long version() {
    return version;
  }

  public Status status() {
    return status;
  }

  public DataProvenance provenance() {
    return provenance;
  }

  public Registration registration() {
    return new Registration(id, sex, name, breedId, birthDate);
  }

  public enum Sex {
    MALE,
    FEMALE,
    UNKNOWN
  }

  public enum Status {
    ACTIVE,
    INACTIVE,
    DECEASED,
    ARCHIVED
  }

  public enum Role {
    DONOR,
    SIRE,
    RECIPIENT
  }

  public record Registration(UUID id, Sex sex, String name, UUID breedId, LocalDate birthDate) {
    public Registration {
      StableIds.requireVersion7(id);
      if (sex == null || (name != null && (name.isBlank() || name.length() > 200)))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_ANIMAL",
            "Animal sex is required and name must be nonblank when supplied");
      if (name != null) name = name.strip();
    }
  }
}
