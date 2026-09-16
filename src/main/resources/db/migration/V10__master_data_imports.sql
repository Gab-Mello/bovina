CREATE TABLE import_batch (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    kind varchar(32) NOT NULL CHECK (kind='CLIENT_MASTER_DATA'),
    mode varchar(16) NOT NULL CHECK (mode IN ('ATOMIC','PARTIAL')),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    source_document_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE TABLE import_item_result (
    organization_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_id uuid NOT NULL,
    client_id uuid,
    status varchar(16) NOT NULL CHECK (status IN ('APPLIED','REJECTED','NOT_APPLIED')),
    error_code varchar(64),
    PRIMARY KEY (organization_id,batch_id,item_id),
    FOREIGN KEY (organization_id,batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,client_id) REFERENCES party(organization_id,id),
    CHECK ((status='APPLIED' AND client_id IS NOT NULL AND error_code IS NULL)
        OR (status<>'APPLIED' AND client_id IS NULL AND error_code IS NOT NULL))
);
CREATE INDEX import_client_idx ON import_item_result(organization_id,client_id) WHERE client_id IS NOT NULL;
ALTER TABLE party ADD CONSTRAINT party_import_batch_fk FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id);
ALTER TABLE animal ADD CONSTRAINT animal_import_batch_fk FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id);
GRANT SELECT,INSERT ON import_batch,import_item_result TO bovina_runtime;
-- FOR UPDATE protects the batch without permitting changes to immutable intent metadata.
GRANT UPDATE(id) ON import_batch TO bovina_runtime;
