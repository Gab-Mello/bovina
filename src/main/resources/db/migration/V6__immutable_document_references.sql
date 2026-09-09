-- One immutable reference identifies one declared source revision; no blob upload service yet.
CREATE TABLE document_reference (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    type varchar(64) NOT NULL CHECK (btrim(type) <> ''),
    reference varchar(500) NOT NULL CHECK (btrim(reference) <> ''),
    revision varchar(80) NOT NULL CHECK (btrim(revision) <> ''),
    checksum varchar(64) CHECK (checksum ~ '^[0-9a-f]{64}$'),
    supersedes_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,supersedes_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id)
);
CREATE INDEX document_reference_search_idx ON document_reference(organization_id,type,id);
CREATE INDEX document_supersedes_idx ON document_reference(organization_id,supersedes_id) WHERE supersedes_id IS NOT NULL;
ALTER TABLE party ADD CONSTRAINT party_source_document_fk
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id);
GRANT SELECT, INSERT ON document_reference TO bovina_runtime;
