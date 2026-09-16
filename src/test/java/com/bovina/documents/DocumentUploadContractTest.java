package com.bovina.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bovina.documents.application.Documents;
import com.bovina.platform.application.ApplicationFailure;
import com.bovina.platform.application.StableIds;
import org.junit.jupiter.api.Test;

class DocumentUploadContractTest {
  @Test
  void uploadContentCannotBeChangedThroughCallerOwnedArrays() {
    var bytes = new byte[] {1, 2, 3};
    var upload =
        new Documents.Upload(
            new StableIds().next(), 0, "source.pdf", "application/pdf", null, bytes);
    bytes[0] = 9;
    var returned = upload.bytes();
    returned[1] = 9;
    assertThat(upload.bytes()).containsExactly(1, 2, 3);
  }

  @Test
  void oversizedEmptyAndUnsafeFileNamesAreRejectedBeforePersistence() {
    var id = new StableIds().next();
    assertThatThrownBy(
            () ->
                new Documents.Upload(
                    id, 0, "../source.pdf", "application/pdf", null, new byte[] {1}))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () -> new Documents.Upload(id, 0, "source.pdf", "application/pdf", null, new byte[0]))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () ->
                new Documents.Upload(
                    id,
                    0,
                    "source.pdf",
                    "application/pdf",
                    null,
                    new byte[Documents.MAX_UPLOAD_BYTES + 1]))
        .isInstanceOf(ApplicationFailure.class);
  }
}
