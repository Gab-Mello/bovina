CREATE TABLE document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    type_code varchar(64) NOT NULL CHECK (type_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    current_version_id uuid,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,created_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX document_tenant_created_idx ON document(organization_id,created_at DESC,id);

CREATE TABLE document_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number > 0),
    original_file_name varchar(255) NOT NULL CHECK (btrim(original_file_name) <> ''),
    mime_type varchar(128) NOT NULL CHECK (btrim(mime_type) <> ''),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    checksum varchar(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    uploaded_at timestamptz NOT NULL,
    uploaded_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,document_id,id),
    UNIQUE (organization_id,document_id,version_number),
    FOREIGN KEY (organization_id,document_id) REFERENCES document(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,uploaded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX document_version_history_idx ON document_version(organization_id,document_id,version_number DESC);
ALTER TABLE document ADD CONSTRAINT document_current_version_fk
    FOREIGN KEY (organization_id,id,current_version_id)
    REFERENCES document_version(organization_id,document_id,id);

-- Binary storage is isolated behind the document storage port. Small MVP files remain
-- transactionally consistent with metadata and participate in the same database backup.
CREATE TABLE document_blob (
    organization_id uuid NOT NULL,
    version_id uuid NOT NULL,
    content bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 10485760),
    PRIMARY KEY (organization_id,version_id),
    FOREIGN KEY (organization_id,version_id) REFERENCES document_version(organization_id,id)
);
GRANT SELECT,INSERT,UPDATE(current_version_id,version,status) ON document TO bovina_runtime;
GRANT SELECT,INSERT ON document_version,document_blob TO bovina_runtime;
