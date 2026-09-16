ALTER TABLE import_batch DROP CONSTRAINT import_batch_kind_check;
ALTER TABLE import_batch ADD CONSTRAINT import_batch_kind_check
    CHECK (kind IN ('CLIENT_MASTER_DATA','OPU_COLLECTIONS','MATINGS','EMBRYOS','EMBRYO_EVALUATIONS'));

CREATE TABLE assessment_scheme (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    code varchar(80) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    UNIQUE (organization_id,id)
);
CREATE UNIQUE INDEX assessment_scheme_code_uk ON assessment_scheme(organization_id,lower(code));

CREATE TABLE assessment_scheme_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    scheme_id uuid NOT NULL,
    version_label varchar(80) NOT NULL CHECK (btrim(version_label) <> ''),
    effective_from date,
    status varchar(16) NOT NULL CHECK (status IN ('PUBLISHED','RETIRED')),
    published_by uuid NOT NULL,
    published_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,scheme_id,version_label),
    FOREIGN KEY (organization_id,scheme_id) REFERENCES assessment_scheme(organization_id,id),
    FOREIGN KEY (organization_id,published_by) REFERENCES organization_membership(organization_id,user_account_id)
);

CREATE TABLE assessment_code (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    scheme_version_id uuid NOT NULL,
    dimension varchar(32) NOT NULL CHECK (dimension IN ('DEVELOPMENT_STAGE','QUALITY_GRADE','OTHER')),
    code varchar(80) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_.-]{0,79}$'),
    display_name varchar(200) NOT NULL CHECK (btrim(display_name) <> ''),
    sort_order integer NOT NULL DEFAULT 0,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,scheme_version_id,id),
    UNIQUE (organization_id,scheme_version_id,dimension,code),
    FOREIGN KEY (organization_id,scheme_version_id) REFERENCES assessment_scheme_version(organization_id,id)
);

CREATE TABLE embryo (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    mating_id uuid NOT NULL,
    human_code varchar(160) NOT NULL CHECK (btrim(human_code) <> ''),
    owner_id uuid,
    identified_at timestamptz NOT NULL,
    availability_status varchar(24) NOT NULL CHECK (availability_status IN ('AVAILABLE','RESERVED','TRANSFERRED','SHIPPED_OUT','DISCARDED','DESTROYED')),
    version bigint NOT NULL DEFAULT 0,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,mating_id) REFERENCES mating(organization_id,id),
    FOREIGN KEY (organization_id,owner_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE UNIQUE INDEX embryo_human_code_uk ON embryo(organization_id,lower(human_code));
CREATE INDEX embryo_mating_idx ON embryo(organization_id,mating_id,id);

CREATE TABLE embryo_evaluation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    embryo_id uuid NOT NULL,
    scheme_version_id uuid NOT NULL,
    development_stage_code_id uuid NOT NULL,
    quality_grade_code_id uuid NOT NULL,
    evaluated_at timestamptz NOT NULL,
    evaluator_professional_id uuid,
    notes varchar(2000),
    supersedes_evaluation_id uuid,
    origin_type varchar(32) NOT NULL CHECK (origin_type IN ('MANUAL','IMPORT','API','AI_EXTRACTED_CONFIRMED','SYSTEM_DERIVED')),
    source_document_id uuid,
    import_batch_id uuid,
    api_client_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,embryo_id) REFERENCES embryo(organization_id,id),
    FOREIGN KEY (organization_id,scheme_version_id,development_stage_code_id)
        REFERENCES assessment_code(organization_id,scheme_version_id,id),
    FOREIGN KEY (organization_id,scheme_version_id,quality_grade_code_id)
        REFERENCES assessment_code(organization_id,scheme_version_id,id),
    FOREIGN KEY (organization_id,supersedes_evaluation_id) REFERENCES embryo_evaluation(organization_id,id),
    FOREIGN KEY (organization_id,evaluator_professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,import_batch_id) REFERENCES import_batch(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX embryo_evaluation_history_idx
    ON embryo_evaluation(organization_id,embryo_id,recorded_at DESC,id DESC);

CREATE TABLE embryo_current_assessment (
    organization_id uuid NOT NULL,
    embryo_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    PRIMARY KEY (organization_id,embryo_id),
    UNIQUE (organization_id,evaluation_id),
    FOREIGN KEY (organization_id,embryo_id) REFERENCES embryo(organization_id,id),
    FOREIGN KEY (organization_id,evaluation_id) REFERENCES embryo_evaluation(organization_id,id)
);

CREATE TABLE embryo_hold (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    embryo_id uuid NOT NULL,
    hold_type varchar(48) NOT NULL CHECK (hold_type ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    opened_by uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    released_by uuid,
    released_at timestamptz,
    release_reason varchar(500),
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,embryo_id) REFERENCES embryo(organization_id,id),
    FOREIGN KEY (organization_id,opened_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,released_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((released_by IS NULL) = (released_at IS NULL)),
    CHECK (released_by IS NULL OR (release_reason IS NOT NULL AND btrim(release_reason) <> ''))
);
CREATE UNIQUE INDEX embryo_active_hold_uk
    ON embryo_hold(organization_id,embryo_id,hold_type) WHERE released_at IS NULL;

CREATE TABLE aggregate_embryo_disposition (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    mating_id uuid NOT NULL,
    disposition_code varchar(48) NOT NULL CHECK (disposition_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    quantity integer NOT NULL CHECK (quantity > 0),
    reason varchar(500),
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,mating_id) REFERENCES mating(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX aggregate_disposition_mating_idx
    ON aggregate_embryo_disposition(organization_id,mating_id,id);

CREATE TABLE mating_completion (
    organization_id uuid NOT NULL,
    mating_id uuid NOT NULL,
    produced_count integer NOT NULL CHECK (produced_count >= 0),
    individualized_count integer NOT NULL CHECK (individualized_count >= 0),
    aggregate_disposition_count integer NOT NULL CHECK (aggregate_disposition_count >= 0),
    completed_by uuid NOT NULL,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id,mating_id),
    FOREIGN KEY (organization_id,mating_id) REFERENCES mating(organization_id,id),
    FOREIGN KEY (organization_id,completed_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (produced_count = individualized_count + aggregate_disposition_count)
);

GRANT SELECT,INSERT ON assessment_scheme,assessment_scheme_version,assessment_code TO bovina_runtime;
GRANT SELECT,INSERT,UPDATE ON embryo TO bovina_runtime;
GRANT SELECT,INSERT ON embryo_evaluation,aggregate_embryo_disposition,mating_completion TO bovina_runtime;
GRANT SELECT,INSERT,UPDATE ON embryo_current_assessment,embryo_hold TO bovina_runtime;
