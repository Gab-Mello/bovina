CREATE TABLE establishment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    legal_display_name varchar(200) NOT NULL CHECK (btrim(legal_display_name) <> ''),
    type varchar(24) NOT NULL CHECK (type = 'CPIVE'),
    operating_mode varchar(24) NOT NULL CHECK (operating_mode IN ('COMMERCIAL','OWN_HERD_ONLY')),
    address_line varchar(250) NOT NULL,
    municipality varchar(120) NOT NULL,
    state varchar(80) NOT NULL,
    country varchar(2) NOT NULL,
    postal_code varchar(32),
    registration_issuer varchar(120),
    registration_number varchar(120),
    registration_reference varchar(250),
    registration_document_id uuid,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,registration_document_id) REFERENCES document_reference(organization_id,id),
    CHECK ((registration_issuer IS NULL) = (registration_number IS NULL))
);
CREATE INDEX establishment_name_idx ON establishment(organization_id,lower(legal_display_name),id);

CREATE TABLE operational_location (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    establishment_id uuid NOT NULL,
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    type varchar(24) NOT NULL CHECK (type IN ('LAB','COLLECTION_UNIT','STORAGE','OFFICE','OTHER')),
    timezone varchar(64),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE UNIQUE INDEX location_name_uk ON operational_location(organization_id,establishment_id,lower(name));

CREATE TABLE professional (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    professional_type varchar(32) NOT NULL CHECK (professional_type IN ('VETERINARIAN','EMBRYOLOGIST','TECHNICIAN')),
    linked_user_id uuid,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,linked_user_id) REFERENCES organization_membership(organization_id,user_account_id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX professional_name_idx ON professional(organization_id,lower(name),id);
CREATE INDEX professional_user_idx ON professional(organization_id,linked_user_id) WHERE linked_user_id IS NOT NULL;

CREATE TABLE professional_credential (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    professional_id uuid NOT NULL,
    issuer varchar(120) NOT NULL CHECK (btrim(issuer) <> ''),
    jurisdiction varchar(80),
    number varchar(120) NOT NULL CHECK (btrim(number) <> ''),
    valid_from date NOT NULL,
    valid_until date,
    document_id uuid,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,professional_id,id),
    FOREIGN KEY (organization_id,professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE responsible_technician_assignment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    establishment_id uuid NOT NULL,
    professional_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    credential_issuer varchar(120) NOT NULL,
    credential_jurisdiction varchar(80),
    credential_number varchar(120) NOT NULL,
    document_id uuid,
    valid_from date NOT NULL,
    valid_until date,
    version bigint NOT NULL DEFAULT 0,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,professional_id,credential_id) REFERENCES professional_credential(organization_id,professional_id,id),
    FOREIGN KEY (organization_id,document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX technician_period_idx ON responsible_technician_assignment(organization_id,establishment_id,professional_id,valid_from);
CREATE INDEX technician_professional_idx ON responsible_technician_assignment(organization_id,professional_id,credential_id);
GRANT SELECT, INSERT, UPDATE ON establishment, operational_location, professional TO bovina_runtime;
GRANT SELECT, INSERT ON professional_credential TO bovina_runtime;
GRANT SELECT, INSERT ON responsible_technician_assignment TO bovina_runtime;
GRANT UPDATE(valid_until,version) ON responsible_technician_assignment TO bovina_runtime;
