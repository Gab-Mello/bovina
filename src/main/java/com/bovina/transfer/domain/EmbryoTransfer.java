package com.bovina.transfer.domain;

import com.bovina.platform.application.*;
import com.bovina.platform.domain.DataProvenance;
import java.time.*;
import java.util.UUID;

public record EmbryoTransfer(
    UUID id,
    UUID reservationId,
    UUID embryoId,
    UUID recipientCycleId,
    Instant performedAt,
    String timezone,
    Origin origin,
    UUID thawEventId,
    UUID operatorProfessionalId,
    String notes,
    DataProvenance provenance) {
  public EmbryoTransfer {
    StableIds.requireVersion7(id);
    if (reservationId == null
        || embryoId == null
        || recipientCycleId == null
        || performedAt == null
        || timezone == null
        || origin == null
        || operatorProfessionalId == null
        || provenance == null) throw rejected("INVALID_EMBRYO_TRANSFER");
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException e) {
      throw rejected("INVALID_TRANSFER_TIMEZONE");
    }
    if (notes != null && (notes.isBlank() || notes.length() > 1000))
      throw rejected("INVALID_TRANSFER_NOTES");
    if (notes != null) notes = notes.strip();
    if ((origin == Origin.FRESH) != (thawEventId == null))
      throw rejected("INVALID_TRANSFER_PRESERVATION_EVIDENCE");
  }

  public EmbryoTransfer(
      UUID id,
      UUID reservationId,
      UUID embryoId,
      UUID recipientCycleId,
      Instant performedAt,
      String timezone,
      Origin origin,
      UUID operatorProfessionalId,
      String notes,
      DataProvenance provenance) {
    this(
        id,
        reservationId,
        embryoId,
        recipientCycleId,
        performedAt,
        timezone,
        origin,
        null,
        operatorProfessionalId,
        notes,
        provenance);
  }

  public LocalDate performedOn() {
    return performedAt.atZone(ZoneId.of(timezone)).toLocalDate();
  }

  public enum Origin {
    FRESH,
    THAWED
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Embryo transfer fact is invalid");
  }
}
