CREATE TABLE pregnancy_check (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    transfer_id uuid NOT NULL,
    checked_at timestamptz NOT NULL,
    checked_timezone varchar(64) NOT NULL CHECK (btrim(checked_timezone) <> ''),
    result varchar(24) NOT NULL CHECK (result IN ('PREGNANT','NOT_PREGNANT','INCONCLUSIVE','PREGNANCY_LOSS')),
    method_code varchar(48) NOT NULL CHECK (method_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    observations varchar(2000),
    professional_id uuid NOT NULL,
    supersedes_check_id uuid,
    correction_reason varchar(500),
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
    UNIQUE (organization_id,id,transfer_id),
    FOREIGN KEY (organization_id,transfer_id) REFERENCES embryo_transfer(organization_id,id),
    FOREIGN KEY (organization_id,supersedes_check_id,transfer_id)
        REFERENCES pregnancy_check(organization_id,id,transfer_id),
    FOREIGN KEY (organization_id,professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,confirmed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((supersedes_check_id IS NULL) = (correction_reason IS NULL)),
    CHECK (correction_reason IS NULL OR btrim(correction_reason) <> ''),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL))
);
CREATE INDEX pregnancy_check_history_idx
    ON pregnancy_check(organization_id,transfer_id,checked_at DESC,recorded_at DESC,id DESC);

CREATE TABLE pregnancy_check_invalidation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    transfer_id uuid NOT NULL,
    pregnancy_check_id uuid NOT NULL,
    replacement_check_id uuid,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    invalidated_by uuid NOT NULL,
    invalidated_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,pregnancy_check_id),
    FOREIGN KEY (organization_id,pregnancy_check_id,transfer_id)
        REFERENCES pregnancy_check(organization_id,id,transfer_id),
    FOREIGN KEY (organization_id,replacement_check_id,transfer_id)
        REFERENCES pregnancy_check(organization_id,id,transfer_id),
    FOREIGN KEY (organization_id,invalidated_by)
        REFERENCES organization_membership(organization_id,user_account_id)
);

GRANT SELECT,INSERT ON pregnancy_check,pregnancy_check_invalidation TO bovina_runtime;
