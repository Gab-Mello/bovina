package com.bovina.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.support.integration.AuthenticatedIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class DocumentVersionIT extends AuthenticatedIntegrationTest {
  @Test
  void immutableVersionsKeepOriginalBytesAndCannotCrossTenantBoundaries() throws Exception {
    var lab = tenant("Evidence Lab");
    var other = tenant("Other Evidence Tenant");
    var documentId = id();
    assertStatus(
        api.post(
            lab.id(),
            "/documents",
            documentId,
            Map.of("id", documentId, "typeCode", "SOURCE_EVIDENCE")),
        201);

    var first = id();
    var original = "evidence-one".getBytes(StandardCharsets.UTF_8);
    var uploaded =
        api.putDocumentVersion(
            lab.id(), documentId, first, 0, "source.txt", "text/plain", original);
    assertStatus(uploaded, 201);
    assertThat(uploaded.body())
        .contains("1a51e28adffef1830ace3db8a23aed197b5a029c2bd05fa8a7a1e83eecf49e3c");
    assertStatus(
        api.putDocumentVersion(
            lab.id(), documentId, first, 0, "source.txt", "text/plain", original),
        201);
    assertStatus(
        api.putDocumentVersion(
            lab.id(),
            documentId,
            first,
            0,
            "source.txt",
            "text/plain",
            "changed".getBytes(StandardCharsets.UTF_8)),
        409);
    assertStatus(api.get(other.id(), "/documents/" + documentId), 404);
    assertThat(api.getDocumentContent(other.id(), documentId, first).statusCode()).isEqualTo(404);

    var second = id();
    assertStatus(
        api.putDocumentVersion(
            lab.id(),
            documentId,
            second,
            1,
            "revision.txt",
            "text/plain",
            "evidence-two".getBytes(StandardCharsets.UTF_8)),
        201);
    var previous = api.getDocumentContent(lab.id(), documentId, first);
    assertThat(previous.statusCode()).isEqualTo(200);
    assertThat(previous.body()).isEqualTo(original);
    assertThat(previous.headers().firstValue("Content-Disposition").orElseThrow())
        .contains("attachment");
    assertThat(previous.headers().firstValue("X-Content-Type-Options").orElseThrow())
        .isEqualTo("nosniff");
    assertThat(api.get(lab.id(), "/documents/" + documentId).body()).contains(second.toString());
    assertThat(api.get(lab.id(), "/documents/" + documentId + "/versions").body())
        .contains(first.toString(), second.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM document_version WHERE organization_id=? AND document_id=?",
                Integer.class,
                lab.id(),
                documentId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=? AND action='UPLOAD'",
                Integer.class,
                lab.id(),
                first))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE document_blob SET content=? WHERE organization_id=? AND version_id=?",
                    "rewrite".getBytes(StandardCharsets.UTF_8),
                    lab.id(),
                    first))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE document_version SET checksum=? WHERE organization_id=? AND id=?",
                    "0".repeat(64),
                    lab.id(),
                    first))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO document_version(id,organization_id,document_id,version_number,original_file_name,mime_type,size_bytes,checksum,origin_type,uploaded_at,uploaded_by) VALUES (?,?,?,?,?,?,?,?,'MANUAL',?,?)",
                    id(),
                    other.id(),
                    documentId,
                    3,
                    "foreign.txt",
                    "text/plain",
                    1,
                    "0".repeat(64),
                    Timestamp.from(Instant.parse("2026-09-01T12:00:00Z")),
                    other.actorId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void simultaneousRetriesCreateOneVersionAndOneAuditFact() throws Exception {
    var lab = tenant("Retry Evidence Lab");
    var documentId = id();
    assertStatus(
        api.post(
            lab.id(),
            "/documents",
            documentId,
            Map.of("id", documentId, "typeCode", "SOURCE_EVIDENCE")),
        201);
    var versionId = id();
    var bytes = "concurrent-upload".getBytes(StandardCharsets.UTF_8);
    var start = new CyclicBarrier(3);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 3; index++) {
        requests.add(
            executor.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return api.putDocumentVersion(
                      lab.id(), documentId, versionId, 0, "race.txt", "text/plain", bytes);
                }));
      }
      for (var request : requests) assertStatus(request.get(20, TimeUnit.SECONDS), 201);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM document_version WHERE organization_id=? AND id=?",
                Integer.class,
                lab.id(),
                versionId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM document_blob WHERE organization_id=? AND version_id=?",
                Integer.class,
                lab.id(),
                versionId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE organization_id=? AND entity_id=? AND action='UPLOAD'",
                Integer.class,
                lab.id(),
                versionId))
        .isEqualTo(1);
  }
}
