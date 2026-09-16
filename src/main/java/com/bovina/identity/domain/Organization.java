package com.bovina.identity.domain;

import com.bovina.platform.application.StableIds;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class Organization {
  public enum Status {
    ACTIVE,
    SUSPENDED,
    CLOSED
  }

  @Id private UUID id;

  @Column(name = "legal_name", nullable = false, length = 200)
  private String legalName;

  @Column(name = "trade_name", length = 200)
  private String tradeName;

  @Column(name = "tax_id", nullable = false, length = 32)
  private String taxId;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Column(name = "default_locale", nullable = false, length = 35)
  private String defaultLocale;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Version private Long version;

  protected Organization() {}

  public Organization(
      UUID id,
      String legalName,
      String tradeName,
      String taxId,
      ZoneId timezone,
      UUID createdBy,
      Instant createdAt) {
    this.id = StableIds.requireVersion7(id);
    if (legalName == null
        || legalName.isBlank()
        || legalName.length() > 200
        || taxId == null
        || taxId.isBlank()
        || taxId.length() > 32
        || (tradeName != null && tradeName.length() > 200))
      throw new IllegalArgumentException("Invalid organization identity");
    this.legalName = legalName.strip();
    this.tradeName = tradeName;
    this.taxId = taxId.strip();
    this.timezone = Objects.requireNonNull(timezone).getId();
    this.defaultLocale = Locale.forLanguageTag("pt-BR").toLanguageTag();
    this.createdBy = Objects.requireNonNull(createdBy);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.status = Status.ACTIVE;
  }

  public UUID id() {
    return id;
  }
}
