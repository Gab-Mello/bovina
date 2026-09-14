package com.bovina.opu.domain;

import com.bovina.platform.application.*;
import java.time.*;
import java.util.*;

/** Immutable observations recorded on arrival; not a transport approval or allocation. */
public record TransportReceipt(
    UUID id,
    UUID sourceSessionId,
    UUID destinationEstablishmentId,
    UUID destinationOperationalLocationId,
    Instant dispatchedAt,
    Instant receivedAt,
    UUID protocolVersionId,
    LocalDate protocolAppliedOn,
    UUID sourceDocumentId,
    String notes,
    List<Item> items) {
  public TransportReceipt {
    StableIds.requireVersion7(id);
    if (sourceSessionId == null
        || destinationEstablishmentId == null
        || receivedAt == null
        || (dispatchedAt != null && receivedAt.isBefore(dispatchedAt))
        || ((protocolVersionId == null) != (protocolAppliedOn == null))
        || (notes != null && notes.length() > 2000)
        || items == null
        || items.isEmpty()
        || items.size() > 100
        || items.stream().anyMatch(Objects::isNull))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_TRANSPORT_OBSERVATION",
          "Arrival, destination and one to 100 collection references are required; receipt cannot precede dispatch");
    items = List.copyOf(items);
    receivedAt = receivedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (dispatchedAt != null)
      dispatchedAt = dispatchedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (items.stream().map(Item::collectionId).distinct().count() != items.size())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "DUPLICATE_TRANSPORT_ITEM",
          "Collection references must be distinct");
  }

  public record Item(UUID collectionId, Integer quantityAtDispatch, Integer quantityAtReceipt) {
    public Item {
      if (collectionId == null
          || (quantityAtDispatch != null && quantityAtDispatch < 0)
          || (quantityAtReceipt != null && quantityAtReceipt < 0))
        throw new ApplicationFailure(
            ApplicationFailure.Kind.REJECTED,
            "INVALID_TRANSPORT_QUANTITY",
            "Observed quantities cannot be negative");
    }
  }
}
