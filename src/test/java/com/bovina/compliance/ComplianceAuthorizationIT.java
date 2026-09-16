package com.bovina.compliance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.compliance.application.RecordCorrections;
import com.bovina.documents.application.Documents;
import com.bovina.identity.application.AuthenticatedIdentity;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ComplianceAuthorizationIT extends AuthenticatedIntegrationTest {
  @Autowired private TenantAccess access;
  @Autowired private Documents documents;
  @Autowired private RecordCorrections corrections;

  @Test
  void readOnlyCanReadDefinitionsButCannotWriteDocumentsOrReviewCorrectionsBeyondHttp()
      throws Exception {
    var lab = tenant("Compliance Access Lab");
    var reader = "reader-" + id();
    assertStatus(
        api.post(
            lab.id(), "/memberships", Map.of("id", id(), "subject", reader, "role", "READ_ONLY")),
        201);
    var c = access.resolve(new AuthenticatedIdentity(issuer(), reader), lab.id(), id());
    var document = id();
    assertThatThrownBy(
            () ->
                documents.register(
                    c, document, new Documents.Registration(document, "SOURCE_EVIDENCE")))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    assertThatThrownBy(
            () -> corrections.reject(c, id(), id(), new RecordCorrections.Rejection("Review")))
        .isInstanceOf(ApplicationFailure.class)
        .extracting("code")
        .isEqualTo("ACCESS_DENIED");
    assertStatus(
        api.postAs(
            reader,
            lab.id(),
            "/documents",
            document,
            Map.of("id", document, "typeCode", "SOURCE_EVIDENCE")),
        403);
    assertStatus(api.getAs(reader, lab.id(), "/compliance/rules"), 200);
  }
}
