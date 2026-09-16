CREATE TABLE breed (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    code varchar(80),
    species varchar(16) NOT NULL CHECK (species = 'BOVINE'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE UNIQUE INDEX breed_code_uk ON breed(organization_id,lower(code)) WHERE code IS NOT NULL;
CREATE INDEX breed_name_idx ON breed(organization_id,lower(name),id);
CREATE TABLE animal (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    species varchar(16) NOT NULL CHECK (species = 'BOVINE'),
    sex varchar(16) NOT NULL CHECK (sex IN ('MALE','FEMALE','UNKNOWN')),
    name varchar(200),
    breed_id uuid,
    birth_date date,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE','DECEASED','ARCHIVED')),
    version bigint NOT NULL DEFAULT 0,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    confirmed_by uuid,
    confirmed_at timestamptz,
    derivation_reference varchar(256),
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,breed_id) REFERENCES breed(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,confirmed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CHECK (origin_type <> 'IMPORT' OR import_batch_id IS NOT NULL),
    CHECK (origin_type <> 'API' OR api_client_id IS NOT NULL),
    CHECK (origin_type <> 'AI_EXTRACTED_CONFIRMED' OR (confirmed_by IS NOT NULL AND source_document_id IS NOT NULL)),
    CHECK (origin_type <> 'SYSTEM_DERIVED' OR (derivation_reference IS NOT NULL AND btrim(derivation_reference) <> ''))
);
CREATE INDEX animal_name_idx ON animal(organization_id,lower(name),id);
CREATE INDEX animal_breed_idx ON animal(organization_id,breed_id);

CREATE TABLE animal_identifier (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    animal_id uuid NOT NULL,
    type varchar(24) NOT NULL CHECK (type IN ('RGD','CGD','CEIP','CEGDF','RGN','EAR_TAG','EID','OTHER')),
    value varchar(160) NOT NULL CHECK (btrim(value) <> ''),
    normalized_value varchar(160) NOT NULL CHECK (btrim(normalized_value) <> ''),
    issuer varchar(120),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','CORRECTED','REVOKED')),
    valid_from date,
    valid_until date,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    retired_by uuid,
    retired_at timestamptz,
    retirement_reason varchar(500),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,animal_id) REFERENCES animal(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,retired_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from),
    CHECK ((status='ACTIVE' AND retired_by IS NULL AND retired_at IS NULL AND retirement_reason IS NULL)
        OR (status<>'ACTIVE' AND retired_by IS NOT NULL AND retired_at IS NOT NULL AND retirement_reason IS NOT NULL AND btrim(retirement_reason)<>''))
);
-- Unknown issuer is one scope, not an escape hatch around active uniqueness.
CREATE UNIQUE INDEX animal_identifier_active_uk ON animal_identifier
    (organization_id,type,issuer,normalized_value) NULLS NOT DISTINCT WHERE status='ACTIVE';
CREATE INDEX animal_identifier_animal_idx ON animal_identifier(organization_id,animal_id,id);

CREATE TABLE animal_ownership_assignment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    animal_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_until date,
    source_document_id uuid,
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,animal_id) REFERENCES animal(organization_id,id),
    FOREIGN KEY (organization_id,owner_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX animal_ownership_period_idx ON animal_ownership_assignment(organization_id,animal_id,owner_id,valid_from);
CREATE INDEX animal_ownership_owner_idx ON animal_ownership_assignment(organization_id,owner_id,animal_id);
GRANT SELECT,INSERT,UPDATE ON breed,animal TO bovina_runtime;
GRANT SELECT,INSERT ON animal_identifier,animal_ownership_assignment TO bovina_runtime;
GRANT UPDATE(status,retired_by,retired_at,retirement_reason,version) ON animal_identifier TO bovina_runtime;
GRANT UPDATE(valid_until,version) ON animal_ownership_assignment TO bovina_runtime;
