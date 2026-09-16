CREATE TABLE shipment_destination_snapshot (
    organization_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    recipient_name varchar(200) NOT NULL,
    recipient_legal_name varchar(200),
    recipient_version bigint NOT NULL,
    property_name varchar(200),
    property_version bigint,
    address_line varchar(250) NOT NULL,
    municipality varchar(120) NOT NULL,
    state varchar(80) NOT NULL,
    country varchar(2) NOT NULL,
    postal_code varchar(32),
    dispatched_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL,
    compliance_result varchar(24) NOT NULL CHECK (compliance_result IN ('PASS','FAIL','UNKNOWN','NOT_APPLICABLE')),
    compliance_explanation varchar(160) NOT NULL,
    regulated_dispatch boolean NOT NULL,
    PRIMARY KEY (organization_id,shipment_id),
    FOREIGN KEY (organization_id,shipment_id) REFERENCES shipment(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (NOT regulated_dispatch OR compliance_result IN ('PASS','NOT_APPLICABLE'))
);
CREATE INDEX shipment_destination_time_idx ON shipment_destination_snapshot(organization_id,dispatched_at,shipment_id);
CREATE TABLE shipment_document_reference (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    type_code varchar(80) NOT NULL CHECK (type_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    document_number varchar(160),
    issuer varchar(200),
    issued_at timestamptz,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,shipment_id,type_code,document_version_id),
    FOREIGN KEY (organization_id,shipment_id) REFERENCES shipment(organization_id,id),
    FOREIGN KEY (organization_id,document_id,document_version_id) REFERENCES document_version(organization_id,document_id,id)
);
CREATE INDEX shipment_document_version_idx ON shipment_document_reference(organization_id,document_version_id,shipment_id);
CREATE TABLE shipment_receipt (
    organization_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    received_at timestamptz NOT NULL,
    notes varchar(2000),
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL,
    PRIMARY KEY (organization_id,shipment_id),
    FOREIGN KEY (organization_id,shipment_id) REFERENCES shipment(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE TABLE shipment_cancellation (
    organization_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    cancelled_at timestamptz NOT NULL,
    cancelled_by uuid NOT NULL,
    PRIMARY KEY (organization_id,shipment_id),
    FOREIGN KEY (organization_id,shipment_id) REFERENCES shipment(organization_id,id),
    FOREIGN KEY (organization_id,cancelled_by) REFERENCES organization_membership(organization_id,user_account_id)
);
GRANT SELECT,INSERT ON shipment_destination_snapshot,shipment_document_reference,shipment_receipt,shipment_cancellation TO bovina_runtime;
