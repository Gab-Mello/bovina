package com.bovina.identity;

import static com.bovina.support.fixture.MasterDataFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.bovina.identity.application.*;
import com.bovina.parties.application.ClientImports;
import com.bovina.protocols.application.Protocols;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MasterDataAuthorizationIT extends AuthenticatedIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private Protocols protocols;
  @Autowired private ClientImports imports;

  private com.bovina.platform.application.ExecutionContext context(UUID tenant) {
    return access.resolve(
        new AuthenticatedIdentity(issuer(), "integration-bootstrap"), tenant, id());
  }

  @Test
  void masterDataRbacIsEnforcedBeyondTheController() throws Exception {
    var tenant = tenant().id();
    var reader = "reader-" + id();
    var grant =
        api.post(
            tenant, "/memberships", Map.of("id", id(), "subject", reader, "role", "READ_ONLY"));
    assertThat(grant.statusCode()).as(grant.body()).isEqualTo(201);
    var context =
        access.resolve(
            new com.bovina.identity.application.AuthenticatedIdentity(issuer(), reader),
            tenant,
            id());
    assertThatThrownBy(
            () ->
                protocols.register(
                    context,
                    id(),
                    new com.bovina.protocols.application.Protocols.Register(
                        id(), "OOCYTE_TRANSPORT", "Denied", null)))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    var batch =
        json.readValue(
            json.writeValueAsString(
                importBatch(id(), "ATOMIC", List.of(importRow(id(), "Denied")))),
            com.bovina.parties.domain.ClientImportBatch.class);
    assertThatThrownBy(() -> imports.commit(context, batch.batchId(), batch))
        .isInstanceOf(com.bovina.platform.application.ApplicationFailure.class);
    assertStatus(
        api.postAs(reader, tenant, "/animals", id(), Map.of("id", id(), "sex", "FEMALE")), 403);
  }

  private Map<String, Object> importRow(UUID client, String name) {
    return Map.of(
        "itemId",
        id(),
        "id",
        client,
        "type",
        "PERSON",
        "displayName",
        name,
        "occurredAt",
        Instant.EPOCH);
  }

  private Map<String, Object> importBatch(
      UUID batch, String mode, List<Map<String, Object>> items) {
    return Map.of("batchId", batch, "mode", mode, "items", items);
  }
}
