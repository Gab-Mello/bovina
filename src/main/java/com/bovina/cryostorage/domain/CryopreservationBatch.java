package com.bovina.cryostorage.domain;

import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.StableIds;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record CryopreservationBatch(
    UUID eventId,
    UUID establishmentId,
    Instant occurredAt,
    String methodCode,
    UUID protocolVersionId,
    UUID professionalId,
    String batchReference,
    String notes,
    List<Item> items) {
  public CryopreservationBatch {
    StableIds.requireVersion7(eventId);
    StableIds.requireVersion7(establishmentId);
    StableIds.requireVersion7(professionalId);
    if (protocolVersionId != null) StableIds.requireVersion7(protocolVersionId);
    if (occurredAt == null
        || methodCode == null
        || !methodCode.matches("[A-Z][A-Z0-9_]{0,79}")
        || items == null
        || items.isEmpty()
        || items.size() > 100
        || (batchReference != null && batchReference.length() > 160)
        || (notes != null && notes.length() > 2000)) throw rejected();
    items = List.copyOf(items);
    if (new HashSet<>(items.stream().map(Item::id).toList()).size() != items.size()
        || new HashSet<>(items.stream().map(Item::embryoId).toList()).size() != items.size())
      throw rejected();
  }

  public record Item(UUID id, UUID embryoId, long expectedEmbryoVersion) {
    public Item {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(embryoId);
      if (expectedEmbryoVersion < 0) throw rejected();
    }
  }

  private static ApplicationFailure rejected() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED,
        "INVALID_CRYOPRESERVATION_BATCH",
        "Cryopreservation command is invalid");
  }
}
