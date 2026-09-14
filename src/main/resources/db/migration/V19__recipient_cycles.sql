CREATE TABLE recipient_cycle (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    recipient_animal_id uuid NOT NULL,
    protocol_version_id uuid,
    opened_on date NOT NULL,
    closed_on date,
    status varchar(16) NOT NULL CHECK (status IN ('OPEN','CLOSED','CANCELLED')),
    notes varchar(1000),
    closure_reason varchar(500),
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
    FOREIGN KEY (organization_id,recipient_animal_id) REFERENCES animal(organization_id,id),
    FOREIGN KEY (organization_id,protocol_version_id) REFERENCES protocol_version(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,confirmed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL)),
    CHECK ((status = 'OPEN' AND closed_on IS NULL AND closure_reason IS NULL)
        OR (status IN ('CLOSED','CANCELLED') AND closed_on IS NOT NULL
            AND closure_reason IS NOT NULL AND btrim(closure_reason) <> '')),
    CHECK (closed_on IS NULL OR closed_on >= opened_on)
);
CREATE INDEX recipient_cycle_history_idx
    ON recipient_cycle(organization_id,recipient_animal_id,opened_on DESC,id DESC);
CREATE INDEX recipient_cycle_open_idx
    ON recipient_cycle(organization_id,opened_on,id) WHERE status = 'OPEN';

GRANT SELECT,INSERT ON recipient_cycle TO bovina_runtime;
GRANT UPDATE(status,closed_on,closure_reason,version) ON recipient_cycle TO bovina_runtime;
