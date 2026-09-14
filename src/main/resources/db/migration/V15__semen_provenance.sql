CREATE TABLE external_establishment_reference (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    legal_party_id uuid,
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    establishment_type varchar(48) NOT NULL CHECK (establishment_type ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    registration_number varchar(120),
    registration_authority varchar(160),
    country char(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    verification_status varchar(48) NOT NULL CHECK (verification_status ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    verification_document_id uuid,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,legal_party_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,verification_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX external_establishment_name_idx
    ON external_establishment_reference(organization_id,lower(name),id);

CREATE TABLE semen_batch (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    batch_code varchar(160) NOT NULL CHECK (btrim(batch_code) <> ''),
    sire_id uuid NOT NULL,
    producer_establishment_id uuid NOT NULL,
    provenance_code varchar(48) NOT NULL CHECK (provenance_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    verification_status varchar(48) NOT NULL CHECK (verification_status ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    semen_type varchar(48) CHECK (semen_type ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    owner_id uuid,
    received_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,sire_id) REFERENCES animal(organization_id,id),
    FOREIGN KEY (organization_id,producer_establishment_id) REFERENCES external_establishment_reference(organization_id,id),
    FOREIGN KEY (organization_id,owner_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX semen_batch_code_idx ON semen_batch(organization_id,lower(batch_code),id);
CREATE INDEX semen_batch_sire_idx ON semen_batch(organization_id,sire_id,id);
CREATE INDEX semen_batch_producer_idx ON semen_batch(organization_id,producer_establishment_id,id);

GRANT SELECT,INSERT ON external_establishment_reference,semen_batch TO bovina_runtime;
