CREATE TABLE embryo_transfer_reservation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    embryo_id uuid NOT NULL,
    recipient_cycle_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','CANCELLED','CONSUMED')),
    reserved_by uuid NOT NULL,
    reserved_at timestamptz NOT NULL,
    ended_by uuid,
    ended_at timestamptz,
    end_reason varchar(500),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,id,embryo_id,recipient_cycle_id),
    FOREIGN KEY (organization_id,embryo_id) REFERENCES embryo(organization_id,id),
    FOREIGN KEY (organization_id,recipient_cycle_id) REFERENCES recipient_cycle(organization_id,id),
    FOREIGN KEY (organization_id,reserved_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,ended_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((status = 'ACTIVE' AND ended_by IS NULL AND ended_at IS NULL AND end_reason IS NULL)
        OR (status IN ('CANCELLED','CONSUMED') AND ended_by IS NOT NULL AND ended_at IS NOT NULL
            AND end_reason IS NOT NULL AND btrim(end_reason) <> ''))
);
CREATE UNIQUE INDEX embryo_active_transfer_reservation_uk
    ON embryo_transfer_reservation(organization_id,embryo_id) WHERE status = 'ACTIVE';
CREATE INDEX transfer_reservation_cycle_idx
    ON embryo_transfer_reservation(organization_id,recipient_cycle_id,reserved_at,id);

CREATE TABLE embryo_transfer (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    reservation_id uuid NOT NULL,
    embryo_id uuid NOT NULL,
    recipient_cycle_id uuid NOT NULL,
    performed_at timestamptz NOT NULL,
    performed_timezone varchar(64) NOT NULL CHECK (btrim(performed_timezone) <> ''),
    transfer_origin varchar(16) NOT NULL CHECK (transfer_origin IN ('FRESH','THAWED')),
    operator_professional_id uuid NOT NULL,
    notes varchar(1000),
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
    UNIQUE (organization_id,embryo_id),
    UNIQUE (organization_id,reservation_id),
    FOREIGN KEY (organization_id,reservation_id,embryo_id,recipient_cycle_id)
        REFERENCES embryo_transfer_reservation(organization_id,id,embryo_id,recipient_cycle_id),
    FOREIGN KEY (organization_id,embryo_id) REFERENCES embryo(organization_id,id),
    FOREIGN KEY (organization_id,recipient_cycle_id) REFERENCES recipient_cycle(organization_id,id),
    FOREIGN KEY (organization_id,operator_professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,confirmed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL))
);
CREATE INDEX embryo_transfer_cycle_idx
    ON embryo_transfer(organization_id,recipient_cycle_id,performed_at DESC,id DESC);
CREATE INDEX embryo_transfer_followup_idx
    ON embryo_transfer(organization_id,performed_at,id);

GRANT SELECT,INSERT ON embryo_transfer TO bovina_runtime;
GRANT SELECT,INSERT ON embryo_transfer_reservation TO bovina_runtime;
GRANT UPDATE(status,ended_by,ended_at,end_reason,version) ON embryo_transfer_reservation TO bovina_runtime;
