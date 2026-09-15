CREATE TABLE inventory_reconciliation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    establishment_id uuid NOT NULL,
    scope_location_id uuid,
    method varchar(16) NOT NULL CHECK (method IN ('SCAN','MANUAL','MIXED')),
    status varchar(16) NOT NULL CHECK (status IN ('OPEN','CLOSED')),
    snapshot_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    started_by uuid NOT NULL,
    closed_at timestamptz,
    closed_by uuid,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    notes varchar(1000),
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,id,establishment_id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,establishment_id,scope_location_id)
        REFERENCES storage_location(organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,started_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,closed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((status='OPEN' AND closed_at IS NULL AND closed_by IS NULL)
        OR (status='CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL))
);

CREATE TABLE reconciliation_expected (
    organization_id uuid NOT NULL,
    reconciliation_id uuid NOT NULL,
    package_id uuid NOT NULL,
    expected_location_id uuid,
    expected_sequence bigint NOT NULL CHECK (expected_sequence >= 0),
    PRIMARY KEY (organization_id,reconciliation_id,package_id),
    FOREIGN KEY (organization_id,reconciliation_id) REFERENCES inventory_reconciliation(organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,expected_location_id) REFERENCES storage_location(organization_id,id)
);

CREATE TABLE reconciliation_observation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    reconciliation_id uuid NOT NULL,
    package_id uuid,
    raw_identifier varchar(160),
    observed_location_id uuid,
    observed_at timestamptz NOT NULL,
    observed_by uuid NOT NULL,
    notes varchar(1000),
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,reconciliation_id) REFERENCES inventory_reconciliation(organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,observed_location_id) REFERENCES storage_location(organization_id,id),
    FOREIGN KEY (organization_id,observed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (package_id IS NOT NULL OR (raw_identifier IS NOT NULL AND btrim(raw_identifier) <> ''))
);
CREATE UNIQUE INDEX reconciliation_package_observed_uk
    ON reconciliation_observation(organization_id,reconciliation_id,package_id)
    WHERE package_id IS NOT NULL;
CREATE INDEX reconciliation_observation_idx
    ON reconciliation_observation(organization_id,reconciliation_id,observed_at,id);

ALTER TABLE inventory_movement ADD CONSTRAINT inventory_movement_reconciliation_fk
    FOREIGN KEY (organization_id,reconciliation_id) REFERENCES inventory_reconciliation(organization_id,id);
CREATE INDEX inventory_movement_reconciliation_idx
    ON inventory_movement(organization_id,reconciliation_id,package_id)
    WHERE reconciliation_id IS NOT NULL;

GRANT SELECT,INSERT ON inventory_reconciliation,reconciliation_expected,reconciliation_observation TO bovina_runtime;
GRANT UPDATE(status,closed_at,closed_by,version) ON inventory_reconciliation TO bovina_runtime;
