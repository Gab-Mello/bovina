package com.bovina.distribution.application;

import com.bovina.audit.application.*;
import com.bovina.compliance.application.MovementDocumentReadiness;
import com.bovina.cryostorage.application.ShipmentInventory;
import com.bovina.distribution.domain.Shipment;
import com.bovina.distribution.infrastructure.*;
import com.bovina.documents.application.Documents;
import com.bovina.identity.application.TenantAccess;
import com.bovina.operations.application.OpuFacilities;
import com.bovina.platform.application.*;
import com.bovina.platform.domain.Address;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Shipments {
  private final TenantAccess access;
  private final ShipmentRepository shipments;
  private final ShipmentEvidence evidence;
  private final ShipmentInventory inventory;
  private final Documents documents;
  private final OpuFacilities facilities;
  private final MovementDocumentReadiness readiness;
  private final DistributionSettings settings;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public Shipments(
      TenantAccess access,
      ShipmentRepository shipments,
      ShipmentEvidence evidence,
      ShipmentInventory inventory,
      Documents documents,
      OpuFacilities facilities,
      MovementDocumentReadiness readiness,
      DistributionSettings settings,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.shipments = shipments;
    this.evidence = evidence;
    this.inventory = inventory;
    this.documents = documents;
    this.facilities = facilities;
    this.readiness = readiness;
    this.settings = settings;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public View prepare(ExecutionContext c, UUID key, Preparation input) {
    access.require(c, "shipment:write");
    if (!key.equals(input.id())) throw rejected("SHIPMENT_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "PREPARE_SHIPMENT_V1",
        input,
        View.class,
        () -> {
          facilities.requireDestination(c.tenantId(), input.establishmentId(), null);
          evidence.destination(c.tenantId(), input.recipientId(), input.propertyId());
          for (var d : input.documents()) documents.version(c, d.documentId(), d.versionId());
          var selections =
              input.items().stream()
                  .map(
                      i ->
                          new ShipmentInventory.Item(
                              i.id(),
                              i.packageId(),
                              i.expectedVersion(),
                              i.expectedLocationId(),
                              0))
                  .toList();
          var quantities = inventory.validateReservation(c, input.establishmentId(), selections);
          var s =
              shipments.saveAndFlush(
                  new Shipment(
                      c.tenantId(),
                      input.id(),
                      input.establishmentId(),
                      input.recipientId(),
                      input.propertyId(),
                      input.purpose(),
                      c.actorId(),
                      now()));
          evidence.items(c.tenantId(), s.id(), input.items(), quantities);
          evidence.documents(c.tenantId(), s.id(), input.documents());
          inventory.reserve(c, evidence.items(c.tenantId(), s.id()));
          recordAudit(c, s, "PREPARE", null, "DRAFT");
          return view(c, s);
        });
  }

  @Transactional
  public View dispatch(ExecutionContext c, UUID key, UUID id, Dispatch input) {
    access.require(c, "shipment:write");
    return receipts.replayOrExecute(
        c,
        key,
        "DISPATCH_SHIPMENT_V1",
        new DispatchIntent(id, input),
        View.class,
        () -> {
          var s = locked(c, id);
          s.requireDraft(input.expectedVersion());
          facilities.requireDestination(c.tenantId(), s.establishmentId(), null);
          var destination = evidence.destination(c.tenantId(), s.recipientId(), s.propertyId());
          for (var d : evidence.documents(c.tenantId(), id))
            documents.version(c, d.documentId(), d.versionId());
          var evaluation =
              readiness.evaluate(
                  s.establishmentId(),
                  s.recipientId(),
                  destination.address().country(),
                  s.purpose(),
                  input.occurredAt());
          readiness.requireDispatch(evaluation, settings.regulatedDispatch());
          inventory.dispatch(
              c, s.establishmentId(), evidence.items(c.tenantId(), id), input.occurredAt());
          evidence.dispatch(
              c,
              id,
              destination,
              input.occurredAt(),
              now(),
              settings.regulatedDispatch(),
              evaluation.result(),
              evaluation.explanationCode());
          s.dispatch(input.expectedVersion());
          shipments.flush();
          recordAudit(c, s, "DISPATCH", "DRAFT", "SHIPPED");
          return view(c, s);
        });
  }

  @Transactional
  public View cancel(ExecutionContext c, UUID key, UUID id, Cancellation input) {
    access.require(c, "shipment:write");
    return receipts.replayOrExecute(
        c,
        key,
        "CANCEL_SHIPMENT_V1",
        new CancelIntent(id, input),
        View.class,
        () -> {
          var s = locked(c, id);
          s.requireDraft(input.expectedVersion());
          inventory.cancel(c, evidence.items(c.tenantId(), id));
          evidence.cancel(c, id, input.reason(), now());
          s.cancel(input.expectedVersion());
          shipments.flush();
          recordAudit(c, s, "CANCEL", "DRAFT", "CANCELLED");
          return view(c, s);
        });
  }

  @Transactional
  public View receive(ExecutionContext c, UUID key, UUID id, Receipt input) {
    access.require(c, "shipment:write");
    return receipts.replayOrExecute(
        c,
        key,
        "RECORD_SHIPMENT_RECEIPT_V1",
        new ReceiptIntent(id, input),
        View.class,
        () -> {
          var s = locked(c, id);
          requireShipped(s, input.expectedVersion());
          if (evidence.received(c.tenantId(), id))
            throw conflict("SHIPMENT_RECEIPT_ALREADY_RECORDED");
          if (input.receivedAt().isBefore(evidence.dispatch(c.tenantId(), id).dispatchedAt()))
            throw rejected("RECEIPT_PRECEDES_DISPATCH");
          evidence.receive(c, id, input.receivedAt(), input.notes(), now());
          recordAudit(c, s, "RECEIVE", null, "RECEIPT_RECORDED_NOT_COMPLIANCE_CERTIFICATION");
          return view(c, s);
        });
  }

  @Transactional
  public View returnPackage(ExecutionContext c, UUID key, UUID id, Return input) {
    access.require(c, "shipment:write");
    if (!key.equals(input.movementId())) throw rejected("RETURN_KEY_MISMATCH");
    return receipts.replayOrExecute(
        c,
        key,
        "RETURN_SHIPMENT_PACKAGE_V1",
        new ReturnIntent(id, input),
        View.class,
        () -> {
          var s = locked(c, id);
          requireShipped(s, input.expectedShipmentVersion());
          var item =
              evidence.items(c.tenantId(), id).stream()
                  .filter(i -> i.id().equals(input.itemId()))
                  .findFirst()
                  .orElseThrow(Shipments::missing);
          inventory.returnPackage(
              c,
              item,
              input.movementId(),
              input.locationId(),
              input.expectedPackageVersion(),
              input.occurredAt(),
              input.reason());
          return view(c, s);
        });
  }

  @Transactional(readOnly = true)
  public View get(ExecutionContext c, UUID id) {
    access.require(c, "shipment:read");
    return view(
        c, shipments.findByOrganizationIdAndId(c.tenantId(), id).orElseThrow(Shipments::missing));
  }

  @Transactional(readOnly = true)
  public PageResult<Summary> page(ExecutionContext c, SearchPage page) {
    access.require(c, "shipment:read");
    return new PageResult<>(evidence.page(c.tenantId(), page), page.page(), page.size());
  }

  @Transactional
  public Validation validate(ExecutionContext c, UUID id, Instant movementAt) {
    access.require(c, "shipment:read");
    if (movementAt == null) throw rejected("MOVEMENT_DATE_REQUIRED");
    var s = locked(c, id);
    var d = evidence.destination(c.tenantId(), s.recipientId(), s.propertyId());
    var docs = evidence.documents(c.tenantId(), id);
    for (var document : docs) documents.version(c, document.documentId(), document.versionId());
    return new Validation(
        readiness.evaluate(
            s.establishmentId(), s.recipientId(), d.address().country(), s.purpose(), movementAt),
        settings.regulatedDispatch(),
        docs.size());
  }

  private Shipment locked(ExecutionContext c, UUID id) {
    return shipments.lock(c.tenantId(), id).orElseThrow(Shipments::missing);
  }

  private View view(ExecutionContext c, Shipment s) {
    return new View(
        s.id(),
        s.status().name(),
        s.version(),
        s.establishmentId(),
        s.recipientId(),
        s.propertyId(),
        s.purpose(),
        evidence.items(c.tenantId(), s.id()),
        evidence.documents(c.tenantId(), s.id()),
        evidence.dispatch(c.tenantId(), s.id()),
        evidence.received(c.tenantId(), s.id()));
  }

  private void recordAudit(
      ExecutionContext c, Shipment s, String action, String before, String after) {
    audit.record(
        new AuditEvent(
            ids.next(), c, now(), action, "SHIPMENT", s.id(), s.version(), null, before, after));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireShipped(Shipment s, long expected) {
    if (s.status() != Shipment.Status.SHIPPED || s.version() != expected)
      throw conflict("SHIPMENT_NOT_SHIPPED_OR_STALE");
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND, "SHIPMENT_NOT_FOUND", "Shipment or item not found");
  }

  private static ApplicationFailure rejected(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED, code, "Invalid shipment command");
  }

  private static ApplicationFailure conflict(String code) {
    return new ApplicationFailure(
        ApplicationFailure.Kind.CONFLICT, code, "Shipment operation conflicts with recorded facts");
  }

  public record Preparation(
      UUID id,
      UUID establishmentId,
      UUID recipientId,
      UUID propertyId,
      String purpose,
      List<PackageSelection> items,
      List<DocumentLink> documents) {
    public Preparation {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(establishmentId);
      StableIds.requireVersion7(recipientId);
      if (items == null || items.isEmpty() || items.size() > 100)
        throw rejected("INVALID_SHIPMENT_ITEMS");
      items = List.copyOf(items);
      documents = documents == null ? List.of() : List.copyOf(documents);
      if (documents.size() > 100
          || new HashSet<>(items.stream().map(PackageSelection::packageId).toList()).size()
              != items.size()
          || new HashSet<>(items.stream().map(PackageSelection::id).toList()).size() != items.size()
          || new HashSet<>(documents.stream().map(DocumentLink::id).toList()).size()
              != documents.size()) throw rejected("DUPLICATE_OR_EXCESSIVE_SHIPMENT_ITEMS");
    }
  }

  public record PackageSelection(
      UUID id, UUID packageId, long expectedVersion, UUID expectedLocationId) {
    public PackageSelection {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(packageId);
      StableIds.requireVersion7(expectedLocationId);
      if (expectedVersion < 0) throw rejected("INVALID_PACKAGE_VERSION");
    }
  }

  public record DocumentLink(
      UUID id,
      String typeCode,
      UUID documentId,
      UUID versionId,
      String number,
      String issuer,
      Instant issuedAt) {
    public DocumentLink {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(documentId);
      StableIds.requireVersion7(versionId);
      if (typeCode == null
          || !typeCode.matches("[A-Z][A-Z0-9_]{0,79}")
          || number != null && number.length() > 160
          || issuer != null && issuer.length() > 200) throw rejected("INVALID_SHIPMENT_DOCUMENT");
    }
  }

  public record Dispatch(long expectedVersion, Instant occurredAt) {
    public Dispatch {
      if (expectedVersion < 0 || occurredAt == null) throw rejected("INVALID_DISPATCH");
    }
  }

  public record Cancellation(long expectedVersion, String reason) {
    public Cancellation {
      if (expectedVersion < 0 || reason == null || reason.isBlank() || reason.length() > 500)
        throw rejected("INVALID_CANCELLATION");
    }
  }

  public record Receipt(long expectedVersion, Instant receivedAt, String notes) {
    public Receipt {
      if (expectedVersion < 0 || receivedAt == null || notes != null && notes.length() > 2000)
        throw rejected("INVALID_RECEIPT");
    }
  }

  public record Return(
      UUID movementId,
      UUID itemId,
      UUID locationId,
      long expectedShipmentVersion,
      long expectedPackageVersion,
      Instant occurredAt,
      String reason) {
    public Return {
      StableIds.requireVersion7(movementId);
      StableIds.requireVersion7(itemId);
      StableIds.requireVersion7(locationId);
      if (expectedShipmentVersion < 0
          || expectedPackageVersion < 0
          || occurredAt == null
          || reason == null
          || reason.isBlank()
          || reason.length() > 500) throw rejected("INVALID_RETURN");
    }
  }

  public record Destination(
      String recipientName,
      String legalName,
      long recipientVersion,
      String propertyName,
      Long propertyVersion,
      Address address) {}

  public record DispatchEvidence(
      Destination destination,
      Instant dispatchedAt,
      boolean regulatedDispatch,
      String complianceResult,
      String complianceExplanation) {}

  public record View(
      UUID id,
      String status,
      long version,
      UUID establishmentId,
      UUID recipientId,
      UUID propertyId,
      String purpose,
      List<ShipmentInventory.Item> items,
      List<DocumentLink> documents,
      DispatchEvidence dispatchEvidence,
      boolean receiptRecorded) {}

  public record Summary(
      UUID id,
      String status,
      long version,
      UUID establishmentId,
      UUID recipientId,
      Instant createdAt) {}

  public record Validation(
      MovementDocumentReadiness.Evaluation evaluation,
      boolean regulatedDispatch,
      int documentCount) {}

  private record DispatchIntent(UUID id, Dispatch input) {}

  private record CancelIntent(UUID id, Cancellation input) {}

  private record ReceiptIntent(UUID id, Receipt input) {}

  private record ReturnIntent(UUID id, Return input) {}
}
