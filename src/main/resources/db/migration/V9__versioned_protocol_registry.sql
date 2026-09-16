CREATE TABLE protocol_definition (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    purpose varchar(48) NOT NULL CHECK (purpose ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    reference varchar(250),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX protocol_purpose_idx ON protocol_definition(organization_id,purpose,lower(name),id);

-- Publication is the only version write in this minimal registry. Revisions are new rows.
CREATE TABLE protocol_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    revision varchar(40) NOT NULL CHECK (btrim(revision) <> ''),
    effective_from date NOT NULL,
    effective_until date,
    content_reference varchar(500) NOT NULL CHECK (btrim(content_reference) <> ''),
    checksum varchar(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    document_id uuid,
    published_by uuid NOT NULL,
    published_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status = 'PUBLISHED'),
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,definition_id,id),
    UNIQUE (organization_id,definition_id,revision),
    FOREIGN KEY (organization_id,definition_id) REFERENCES protocol_definition(organization_id,id),
    FOREIGN KEY (organization_id,document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,published_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (effective_until IS NULL OR effective_until > effective_from)
);
GRANT SELECT,INSERT ON protocol_definition,protocol_version TO bovina_runtime;
GRANT UPDATE(status,version) ON protocol_definition TO bovina_runtime;
