ALTER TABLE import_batch DROP CONSTRAINT import_batch_kind_check;
ALTER TABLE import_batch ADD CONSTRAINT import_batch_kind_check
    CHECK (kind IN ('CLIENT_MASTER_DATA','OPU_COLLECTIONS','MATINGS'));

CREATE TABLE mating (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    oocyte_collection_id uuid NOT NULL,
    semen_batch_id uuid NOT NULL,
    allocated_oocytes integer NOT NULL CHECK (allocated_oocytes > 0),
    fertilized_at timestamptz NOT NULL,
    method varchar(48) NOT NULL CHECK (method ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    responsible_professional_id uuid,
    status varchar(16) NOT NULL CHECK (status IN ('FERTILIZED','COMPLETED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,oocyte_collection_id) REFERENCES oocyte_collection(organization_id,id),
    FOREIGN KEY (organization_id,semen_batch_id) REFERENCES semen_batch(organization_id,id),
    FOREIGN KEY (organization_id,responsible_professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (origin_type <> 'IMPORT' OR import_batch_id IS NOT NULL),
    CHECK (origin_type <> 'API' OR (api_client_id IS NOT NULL AND source_document_id IS NOT NULL))
);
CREATE INDEX mating_collection_idx ON mating(organization_id,oocyte_collection_id,id);
CREATE INDEX mating_semen_batch_idx ON mating(organization_id,semen_batch_id,id);

CREATE TABLE mating_lineage_snapshot (
    organization_id uuid NOT NULL,
    mating_id uuid NOT NULL,
    snapshot jsonb NOT NULL,
    PRIMARY KEY (organization_id,mating_id),
    FOREIGN KEY (organization_id,mating_id) REFERENCES mating(organization_id,id)
);

CREATE TABLE record_correction (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    subject_type varchar(48) NOT NULL CHECK (subject_type ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    subject_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    proposed_change jsonb NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REJECTED','APPLIED')),
    requested_by uuid NOT NULL,
    requested_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,requested_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,subject_id) REFERENCES mating(organization_id,id)
);
CREATE INDEX record_correction_subject_idx
    ON record_correction(organization_id,subject_type,subject_id,requested_at DESC);

GRANT SELECT,INSERT,UPDATE ON mating TO bovina_runtime;
GRANT SELECT,INSERT ON mating_lineage_snapshot,record_correction TO bovina_runtime;
