package com.bovina.distribution;

import static org.assertj.core.api.Assertions.*;

import com.bovina.distribution.application.*;
import com.bovina.identity.application.*;
import com.bovina.platform.application.*;
import com.bovina.support.fixture.DistributionFixtures;
import com.bovina.support.integration.OperationalDistributionTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class DistributionTenantBoundaryIT extends OperationalDistributionTest {
  @Autowired private TenantAccess access;
  @Autowired private Shipments shipments;
  @Autowired private Recalls recalls;

  @Test
  void shipmentAndRecallAssociationsCannotCrossTenantThroughApiOrForeignKeys() throws Exception {
    var lab = tenant("Tenant-safe Distribution Lab");
    var other = tenant("Foreign Destination Tenant");
    var stock = DistributionFixtures.stock(api, lab);
    var recipient = DistributionFixtures.recipient(api, lab);
    var foreignRecipient = DistributionFixtures.recipient(api, other);
    var shipment = id();
    assertStatus(
        api.post(
            lab.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    assertStatus(api.get(other.id(), "/shipments/" + shipment), 404);
    assertStatus(
        api.post(
            other.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch()),
        404);
    var wrong = id();
    assertStatus(
        api.post(
            lab.id(),
            "/shipments",
            wrong,
            DistributionFixtures.preparation(
                wrong,
                foreignRecipient,
                stock,
                List.of(DistributionFixtures.item(stock)),
                List.of())),
        404);
    var doc = id();
    var version = id();
    assertStatus(
        api.post(other.id(), "/documents", doc, Map.of("id", doc, "typeCode", "EVIDENCE")), 201);
    assertStatus(
        api.putDocumentVersion(
            other.id(), doc, version, 0, "source.txt", "text/plain", "evidence".getBytes()),
        201);
    var linked = id();
    assertStatus(
        api.post(
            lab.id(),
            "/shipments",
            linked,
            DistributionFixtures.preparation(
                linked,
                recipient,
                stock,
                List.of(DistributionFixtures.item(stock)),
                List.of(
                    Map.of(
                        "id",
                        id(),
                        "typeCode",
                        "OTHER",
                        "documentId",
                        doc,
                        "versionId",
                        version)))),
        404);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO shipment(id,organization_id,establishment_id,destination_recipient_id,purpose,status,created_at,created_by) VALUES (?,?,?,?,'MOVEMENT','DRAFT',CURRENT_TIMESTAMP,?)",
                    id(),
                    lab.id(),
                    stock.production().inputs().opu().establishment(),
                    foreignRecipient,
                    lab.actorId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO shipment_document_reference(id,organization_id,shipment_id,type_code,document_id,document_version_id) VALUES (?,?,?,'OTHER',?,?)",
                    id(),
                    lab.id(),
                    shipment,
                    doc,
                    version))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO shipment_item(id,organization_id,shipment_id,package_id,expected_package_version,expected_location_id,quantity) VALUES (?,?,?,?,3,?,1)",
                    id(),
                    other.id(),
                    shipment,
                    stock.packaged().packageId(),
                    stock.location()))
        .isInstanceOf(DataIntegrityViolationException.class);
    var recall = id();
    assertStatus(
        api.post(
            other.id(),
            "/recalls",
            recall,
            Map.of(
                "id",
                recall,
                "triggerType",
                "MATING",
                "triggerId",
                stock.production().mating(),
                "reason",
                "Foreign source")),
        404);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO recall_case(id,organization_id,trigger_type,trigger_id,mating_id,reason,opened_at,opened_by) VALUES (?,?,'MATING',?,?,'Foreign source',CURRENT_TIMESTAMP,?)",
                    id(),
                    other.id(),
                    stock.production().mating(),
                    stock.production().mating(),
                    other.actorId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void readOnlyCannotDispatchOrSegregateThroughHttpOrApplicationBoundary() throws Exception {
    var lab = tenant("Distribution Reader Lab");
    var stock = DistributionFixtures.stock(api, lab);
    var recipient = DistributionFixtures.recipient(api, lab);
    var shipment = id();
    assertStatus(
        api.post(
            lab.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment, recipient, stock, List.of(DistributionFixtures.item(stock)), List.of())),
        201);
    var reader = "distribution-reader-" + id();
    assertStatus(
        api.post(
            lab.id(), "/memberships", Map.of("id", id(), "subject", reader, "role", "READ_ONLY")),
        201);
    assertStatus(
        api.postAs(
            reader,
            lab.id(),
            "/shipments/" + shipment + ":dispatch",
            id(),
            DistributionFixtures.dispatch()),
        403);
    var context = access.resolve(new AuthenticatedIdentity(issuer(), reader), lab.id(), id());
    assertThatThrownBy(
            () ->
                shipments.dispatch(
                    context,
                    id(),
                    shipment,
                    new Shipments.Dispatch(0, DistributionFixtures.DISPATCHED_AT)))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    assertThatThrownBy(
            () ->
                recalls.open(
                    context,
                    id(),
                    new Recalls.Open(id(), "MATING", stock.production().mating(), "Denied", null)))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
  }
}
