CREATE TABLE storage_location (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    establishment_id uuid NOT NULL,
    operational_location_id uuid,
    parent_id uuid,
    type_code varchar(80) NOT NULL CHECK (type_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    code varchar(160) NOT NULL CHECK (btrim(code) <> ''),
    name varchar(200),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,establishment_id,operational_location_id)
        REFERENCES operational_location(organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,establishment_id,parent_id)
        REFERENCES storage_location(organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,created_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    CHECK (parent_id IS NULL OR operational_location_id IS NULL)
);
CREATE UNIQUE INDEX storage_root_code_uk
    ON storage_location(organization_id,establishment_id,lower(code)) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX storage_child_code_uk
    ON storage_location(organization_id,parent_id,lower(code)) WHERE parent_id IS NOT NULL;
CREATE INDEX storage_parent_idx ON storage_location(organization_id,establishment_id,parent_id,id);

ALTER TABLE embryo_package
    ADD COLUMN current_location_id uuid,
    ADD COLUMN last_movement_sequence bigint NOT NULL DEFAULT 0 CHECK (last_movement_sequence >= 0),
    ADD CONSTRAINT embryo_package_current_location_fk
        FOREIGN KEY (organization_id,establishment_id,current_location_id)
        REFERENCES storage_location(organization_id,establishment_id,id);
CREATE INDEX embryo_package_location_idx
    ON embryo_package(organization_id,current_location_id,id) WHERE current_location_id IS NOT NULL;
GRANT SELECT,INSERT ON storage_location TO bovina_runtime;
GRANT UPDATE(status,version) ON storage_location TO bovina_runtime;
GRANT UPDATE(current_location_id,last_movement_sequence) ON embryo_package TO bovina_runtime;

CREATE TABLE inventory_movement (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    package_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence > 0),
    movement_type varchar(16) NOT NULL CHECK (movement_type IN
        ('RECEIVE','STORE','MOVE','WITHDRAW','SHIP','RETURN','DISPOSE','ADJUST')),
    from_location_id uuid,
    to_location_id uuid,
    occurred_at timestamptz NOT NULL,
    performed_by uuid NOT NULL,
    reason varchar(500),
    idempotency_key uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    reconciliation_id uuid,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,package_id,sequence),
    UNIQUE (organization_id,idempotency_key),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,from_location_id) REFERENCES storage_location(organization_id,id),
    FOREIGN KEY (organization_id,to_location_id) REFERENCES storage_location(organization_id,id),
    FOREIGN KEY (organization_id,performed_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    CHECK ((movement_type IN ('RECEIVE','STORE','RETURN')
            AND from_location_id IS NULL AND to_location_id IS NOT NULL)
        OR (movement_type='MOVE' AND from_location_id IS NOT NULL
            AND to_location_id IS NOT NULL AND from_location_id <> to_location_id)
        OR (movement_type IN ('WITHDRAW','SHIP','DISPOSE')
            AND from_location_id IS NOT NULL AND to_location_id IS NULL)
        OR (movement_type='ADJUST' AND from_location_id IS DISTINCT FROM to_location_id)),
    CHECK (movement_type NOT IN ('ADJUST','DISPOSE') OR (reason IS NOT NULL AND btrim(reason) <> ''))
);
CREATE INDEX inventory_movement_replay_idx
    ON inventory_movement(organization_id,package_id,sequence);
CREATE INDEX inventory_movement_time_idx
    ON inventory_movement(organization_id,recorded_at,id);
GRANT SELECT,INSERT ON inventory_movement TO bovina_runtime;

CREATE TABLE inventory_hold (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    package_id uuid NOT NULL,
    hold_type varchar(80) NOT NULL CHECK (hold_type ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    opened_at timestamptz NOT NULL,
    opened_by uuid NOT NULL,
    released_at timestamptz,
    released_by uuid,
    release_reason varchar(500),
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,opened_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,released_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL
            AND release_reason IS NOT NULL AND btrim(release_reason) <> ''))
);
CREATE UNIQUE INDEX inventory_active_hold_uk
    ON inventory_hold(organization_id,package_id,hold_type) WHERE released_at IS NULL;
CREATE INDEX inventory_hold_package_idx ON inventory_hold(organization_id,package_id,opened_at,id);
GRANT SELECT,INSERT ON inventory_hold TO bovina_runtime;
GRANT UPDATE(released_at,released_by,release_reason) ON inventory_hold TO bovina_runtime;

CREATE TABLE inventory_hold_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    hold_id uuid NOT NULL,
    event_type varchar(16) NOT NULL CHECK (event_type IN ('OPENED','RELEASED')),
    occurred_at timestamptz NOT NULL,
    actor_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,hold_id,event_type),
    FOREIGN KEY (organization_id,hold_id) REFERENCES inventory_hold(organization_id,id),
    FOREIGN KEY (organization_id,actor_id) REFERENCES organization_membership(organization_id,user_account_id)
);
GRANT SELECT,INSERT ON inventory_hold_event TO bovina_runtime;

CREATE TABLE thaw_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    package_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    professional_id uuid NOT NULL,
    result_code varchar(80) NOT NULL CHECK (result_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    notes varchar(2000),
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,id,package_id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE TABLE thaw_event_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    thaw_event_id uuid NOT NULL,
    package_id uuid NOT NULL,
    package_item_id uuid NOT NULL,
    embryo_id uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,package_item_id),
    UNIQUE (organization_id,thaw_event_id,embryo_id),
    FOREIGN KEY (organization_id,thaw_event_id,package_id)
        REFERENCES thaw_event(organization_id,id,package_id),
    FOREIGN KEY (organization_id,package_item_id,embryo_id)
        REFERENCES package_item(organization_id,id,embryo_id)
);
CREATE INDEX thaw_event_package_idx ON thaw_event(organization_id,package_id,occurred_at,id);
GRANT SELECT,INSERT ON thaw_event,thaw_event_item TO bovina_runtime;
