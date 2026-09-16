package com.bovina.parties.application;

import com.bovina.documents.application.DocumentReferences;
import com.bovina.identity.application.TenantAccess;
import com.bovina.parties.domain.ClientImportBatch;
import com.bovina.parties.domain.ClientImportBatch.*;
import com.bovina.parties.infrastructure.ClientImportStore;
import com.bovina.platform.application.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientImports {
  private final TenantAccess access;
  private final ClientImportTransactions transactions;
  private final ClientImportStore store;
  private final DocumentReferences documents;

  public ClientImports(
      TenantAccess access,
      ClientImportTransactions transactions,
      ClientImportStore store,
      DocumentReferences documents) {
    this.access = access;
    this.transactions = transactions;
    this.store = store;
    this.documents = documents;
  }

  @Transactional(readOnly = true)
  public Result preview(ExecutionContext c, ClientImportBatch batch) {
    access.require(c, "master-data:write");
    if (batch.sourceDocumentId() != null)
      documents.requireReference(c.tenantId(), batch.sourceDocumentId());
    return new Result(
        batch.batchId(),
        batch.mode(),
        true,
        batch.validate(store.existingClients(c.tenantId(), batch)));
  }

  public Result commit(ExecutionContext c, UUID key, ClientImportBatch batch) {
    access.require(c, "master-data:write");
    if (!batch.batchId().equals(key))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "IMPORT_KEY_MISMATCH",
          "Idempotency-Key must equal batchId");
    // Commit immutable intent before any PARTIAL work, so retries after a crash cannot change the
    // batch.
    transactions.bind(c, batch);
    if (batch.mode() == Mode.ATOMIC) {
      try {
        return transactions.atomic(c, batch);
      } catch (DataIntegrityViolationException conflict) {
        return transactions.rejectAtomic(c, batch);
      }
    }
    var results = new ArrayList<ItemResult>();
    for (int i = 0; i < batch.items().size(); i++) {
      var row = batch.items().get(i);
      try {
        results.add(transactions.partialItem(c, batch, row));
      } catch (DataIntegrityViolationException conflict) {
        results.add(transactions.rejectItem(c, batch, row.itemId()));
      }
    }
    return new Result(batch.batchId(), batch.mode(), false, results);
  }
}
