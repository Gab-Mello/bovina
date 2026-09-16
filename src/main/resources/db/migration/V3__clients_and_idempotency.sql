CREATE TABLE party (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    type varchar(16) NOT NULL CHECK (type IN ('PERSON', 'COMPANY')),
    display_name varchar(200) NOT NULL CHECK (btrim(display_name) <> ''),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version bigint NOT NULL DEFAULT 0,
    occurred_at timestamptz NOT NULL,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    confirmed_by uuid,
    confirmed_at timestamptz,
    derivation_reference varchar(256),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, recorded_by) REFERENCES organization_membership(organization_id, user_account_id),
    FOREIGN KEY (organization_id, confirmed_by) REFERENCES organization_membership(organization_id, user_account_id),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CHECK (origin_type <> 'IMPORT' OR import_batch_id IS NOT NULL),
    CHECK (origin_type <> 'API' OR api_client_id IS NOT NULL),
    CHECK (origin_type <> 'AI_EXTRACTED_CONFIRMED' OR (confirmed_by IS NOT NULL AND source_document_id IS NOT NULL)),
    CHECK (origin_type <> 'SYSTEM_DERIVED' OR (derivation_reference IS NOT NULL AND btrim(derivation_reference) <> ''))
);
CREATE INDEX party_recorder_idx ON party(organization_id, recorded_by);
CREATE INDEX party_confirmer_idx ON party(organization_id, confirmed_by) WHERE confirmed_by IS NOT NULL;

CREATE TABLE party_role (
    organization_id uuid NOT NULL,
    party_id uuid NOT NULL,
    role varchar(32) NOT NULL CHECK (role = 'CLIENT'),
    PRIMARY KEY (organization_id, party_id, role),
    FOREIGN KEY (organization_id, party_id) REFERENCES party(organization_id, id)
);

CREATE TABLE idempotent_command (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    command_type varchar(64) NOT NULL,
    idempotency_key uuid NOT NULL,
    actor_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED')),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    response_status integer,
    response_json jsonb,
    resource_id uuid,
    UNIQUE (organization_id, command_type, idempotency_key),
    FOREIGN KEY (organization_id, actor_id) REFERENCES organization_membership(organization_id, user_account_id),
    FOREIGN KEY (organization_id, resource_id) REFERENCES party(organization_id, id),
    CHECK ((status = 'PROCESSING' AND completed_at IS NULL AND response_status IS NULL AND response_json IS NULL AND resource_id IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND response_status = 201 AND response_json IS NOT NULL AND resource_id IS NOT NULL))
);
CREATE INDEX command_actor_idx ON idempotent_command(organization_id, actor_id);
CREATE INDEX command_resource_idx ON idempotent_command(organization_id, resource_id) WHERE resource_id IS NOT NULL;
GRANT SELECT, INSERT, UPDATE ON party TO bovina_runtime;
GRANT SELECT, INSERT ON party_role TO bovina_runtime;
GRANT SELECT, INSERT, UPDATE ON idempotent_command TO bovina_runtime;
