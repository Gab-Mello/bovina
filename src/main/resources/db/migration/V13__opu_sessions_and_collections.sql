ALTER TABLE operational_location ADD CONSTRAINT location_establishment_identity UNIQUE (organization_id,establishment_id,id);
ALTER TABLE import_batch DROP CONSTRAINT import_batch_kind_check;
ALTER TABLE import_batch ADD CONSTRAINT import_batch_kind_check CHECK (kind IN ('CLIENT_MASTER_DATA','OPU_COLLECTIONS'));

CREATE TABLE opu_session (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    establishment_id uuid NOT NULL,
    operational_location_id uuid,
    farm_property_id uuid NOT NULL,
    client_id uuid,
    lead_professional_id uuid NOT NULL,
    performed_at timestamptz NOT NULL,
    timezone varchar(80) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT','IN_PROGRESS','COMPLETED','CANCELLED')),
    notes varchar(2000),
    version bigint NOT NULL DEFAULT 0,
    completed_at timestamptz,
    completed_by uuid,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,establishment_id,operational_location_id) REFERENCES operational_location(organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,farm_property_id) REFERENCES farm_property(organization_id,id),
    FOREIGN KEY (organization_id,client_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,lead_professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,completed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((status='COMPLETED' AND completed_at IS NOT NULL AND completed_by IS NOT NULL)
        OR (status<>'COMPLETED' AND completed_at IS NULL AND completed_by IS NULL)),
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    confirmed_by uuid,
    confirmed_at timestamptz,
    derivation_reference varchar(256),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,confirmed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CHECK (origin_type <> 'IMPORT' OR import_batch_id IS NOT NULL),
    CHECK (origin_type <> 'API' OR (api_client_id IS NOT NULL AND source_document_id IS NOT NULL)),
    CHECK (origin_type <> 'AI_EXTRACTED_CONFIRMED' OR (confirmed_by IS NOT NULL AND source_document_id IS NOT NULL)),
    CHECK (origin_type <> 'SYSTEM_DERIVED' OR derivation_reference IS NOT NULL)
);
CREATE INDEX opu_session_date_idx ON opu_session(organization_id,performed_at DESC,id);
CREATE INDEX opu_session_property_idx ON opu_session(organization_id,farm_property_id);
CREATE INDEX opu_session_establishment_idx ON opu_session(organization_id,establishment_id);
CREATE INDEX opu_session_professional_idx ON opu_session(organization_id,lead_professional_id);

CREATE TABLE oocyte_collection (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    opu_session_id uuid NOT NULL,
    donor_id uuid NOT NULL,
    collected_at timestamptz NOT NULL,
    total_recovered integer NOT NULL CHECK (total_recovered >= 0),
    viable integer NOT NULL CHECK (viable >= 0 AND viable <= total_recovered),
    follicles_aspirated integer CHECK (follicles_aspirated >= 0),
    notes varchar(2000),
    status varchar(16) NOT NULL CHECK (status IN ('RECORDED','COMPLETED')),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,opu_session_id) REFERENCES opu_session(organization_id,id),
    FOREIGN KEY (organization_id,donor_id) REFERENCES animal(organization_id,id),
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    confirmed_by uuid,
    confirmed_at timestamptz,
    derivation_reference varchar(256),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,confirmed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CHECK (origin_type <> 'IMPORT' OR import_batch_id IS NOT NULL),
    CHECK (origin_type <> 'API' OR (api_client_id IS NOT NULL AND source_document_id IS NOT NULL)),
    CHECK (origin_type <> 'AI_EXTRACTED_CONFIRMED' OR (confirmed_by IS NOT NULL AND source_document_id IS NOT NULL)),
    CHECK (origin_type <> 'SYSTEM_DERIVED' OR derivation_reference IS NOT NULL)
);
-- Canonical default (10.2); revisit only if CPIVE validation confirms repeated donor collections.
CREATE UNIQUE INDEX collection_session_donor_uk ON oocyte_collection(organization_id,opu_session_id,donor_id);
CREATE INDEX collection_donor_date_idx ON oocyte_collection(organization_id,donor_id,collected_at);
CREATE TABLE opu_farm_snapshot (
    organization_id uuid NOT NULL,
    session_id uuid NOT NULL,
    snapshot jsonb NOT NULL,
    PRIMARY KEY (organization_id,session_id),
    FOREIGN KEY (organization_id,session_id) REFERENCES opu_session(organization_id,id)
);
CREATE TABLE oocyte_donor_snapshot (
    organization_id uuid NOT NULL,
    collection_id uuid NOT NULL,
    snapshot jsonb NOT NULL,
    PRIMARY KEY (organization_id,collection_id),
    FOREIGN KEY (organization_id,collection_id) REFERENCES oocyte_collection(organization_id,id)
);
GRANT SELECT,INSERT,UPDATE ON opu_session,oocyte_collection TO bovina_runtime;
GRANT SELECT,INSERT ON opu_farm_snapshot,oocyte_donor_snapshot TO bovina_runtime;
