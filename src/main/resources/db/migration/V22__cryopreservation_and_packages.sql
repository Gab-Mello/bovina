CREATE TABLE cryopreservation_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    establishment_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    method_code varchar(80) NOT NULL CHECK (method_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    protocol_version_id uuid,
    professional_id uuid NOT NULL,
    batch_reference varchar(160),
    notes varchar(2000),
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,protocol_version_id) REFERENCES protocol_version(organization_id,id),
    FOREIGN KEY (organization_id,professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX cryopreservation_event_date_idx ON cryopreservation_event(organization_id,occurred_at DESC,id);

ALTER TABLE embryo_evaluation ADD CONSTRAINT embryo_evaluation_embryo_ref_uk
    UNIQUE (organization_id,id,embryo_id);

CREATE TABLE cryopreservation_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    event_id uuid NOT NULL,
    embryo_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    stage_code varchar(80) NOT NULL CHECK (btrim(stage_code) <> ''),
    result_code varchar(80) NOT NULL CHECK (result_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,id,embryo_id),
    UNIQUE (organization_id,event_id,embryo_id),
    FOREIGN KEY (organization_id,event_id) REFERENCES cryopreservation_event(organization_id,id),
    FOREIGN KEY (organization_id,embryo_id) REFERENCES embryo(organization_id,id),
    FOREIGN KEY (organization_id,evaluation_id,embryo_id)
        REFERENCES embryo_evaluation(organization_id,id,embryo_id)
);
CREATE INDEX cryopreservation_item_embryo_idx ON cryopreservation_item(organization_id,embryo_id,event_id);

CREATE TABLE embryo_package (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    establishment_id uuid NOT NULL,
    package_code varchar(160) NOT NULL CHECK (btrim(package_code) <> ''),
    packaging_type varchar(80) NOT NULL CHECK (packaging_type ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    owner_party_id uuid,
    packaged_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT','SEALED','DISPOSED')),
    sealed_at timestamptz,
    sealed_by uuid,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,owner_party_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,sealed_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,created_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((status='DRAFT' AND sealed_at IS NULL AND sealed_by IS NULL)
        OR (status IN ('SEALED','DISPOSED') AND sealed_at IS NOT NULL AND sealed_by IS NOT NULL))
);
CREATE UNIQUE INDEX embryo_package_code_uk ON embryo_package(organization_id,lower(package_code));
CREATE INDEX embryo_package_establishment_idx ON embryo_package(organization_id,establishment_id,status,id);

CREATE TABLE package_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    package_id uuid NOT NULL,
    cryopreservation_item_id uuid NOT NULL,
    embryo_id uuid NOT NULL,
    position_code varchar(80),
    added_at timestamptz NOT NULL,
    added_by uuid NOT NULL,
    removed_at timestamptz,
    removed_by uuid,
    removal_reason varchar(500),
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,id,embryo_id),
    UNIQUE (organization_id,package_id,embryo_id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,cryopreservation_item_id,embryo_id)
        REFERENCES cryopreservation_item(organization_id,id,embryo_id),
    FOREIGN KEY (organization_id,added_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,removed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((removed_at IS NULL AND removed_by IS NULL AND removal_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by IS NOT NULL AND removal_reason IS NOT NULL AND btrim(removal_reason) <> ''))
);
CREATE UNIQUE INDEX package_active_embryo_uk ON package_item(organization_id,embryo_id) WHERE removed_at IS NULL;
CREATE INDEX package_item_members_idx ON package_item(organization_id,package_id,removed_at,id);

CREATE TABLE package_label_snapshot (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    package_id uuid NOT NULL,
    template_version varchar(80) NOT NULL CHECK (btrim(template_version) <> ''),
    payload jsonb NOT NULL,
    checksum varchar(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    generated_at timestamptz NOT NULL,
    generated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,generated_by) REFERENCES organization_membership(organization_id,user_account_id)
);

GRANT SELECT,INSERT ON cryopreservation_event,cryopreservation_item,package_item,package_label_snapshot TO bovina_runtime;
GRANT SELECT,INSERT ON embryo_package TO bovina_runtime;
GRANT UPDATE(status,sealed_at,sealed_by,version) ON embryo_package TO bovina_runtime;
GRANT UPDATE(removed_at,removed_by,removal_reason) ON package_item TO bovina_runtime;
