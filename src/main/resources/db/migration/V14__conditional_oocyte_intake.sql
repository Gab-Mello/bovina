CREATE TABLE oocyte_transport (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    source_session_id uuid NOT NULL,
    destination_establishment_id uuid NOT NULL,
    destination_operational_location_id uuid,
    dispatched_at timestamptz,
    received_at timestamptz NOT NULL,
    protocol_version_id uuid,
    protocol_applied_on date,
    source_document_id uuid,
    notes varchar(2000),
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    origin_type varchar(32) NOT NULL CHECK (origin_type='MANUAL'),
    UNIQUE (organization_id,id,source_session_id),
    FOREIGN KEY (organization_id,source_session_id) REFERENCES opu_session(organization_id,id),
    FOREIGN KEY (organization_id,destination_establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,destination_establishment_id,destination_operational_location_id) REFERENCES operational_location(organization_id,establishment_id,id),
    FOREIGN KEY (organization_id,protocol_version_id) REFERENCES protocol_version(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (dispatched_at IS NULL OR received_at>=dispatched_at),
    CHECK ((protocol_version_id IS NULL)=(protocol_applied_on IS NULL))
);
ALTER TABLE oocyte_collection ADD CONSTRAINT collection_session_identity UNIQUE (organization_id,opu_session_id,id);
CREATE TABLE oocyte_transport_item (
    organization_id uuid NOT NULL,
    transport_id uuid NOT NULL,
    session_id uuid NOT NULL,
    collection_id uuid NOT NULL,
    quantity_at_dispatch integer CHECK (quantity_at_dispatch>=0),
    quantity_at_receipt integer CHECK (quantity_at_receipt>=0),
    PRIMARY KEY (organization_id,transport_id,collection_id),
    FOREIGN KEY (organization_id,transport_id,session_id) REFERENCES oocyte_transport(organization_id,id,source_session_id),
    FOREIGN KEY (organization_id,session_id,collection_id) REFERENCES oocyte_collection(organization_id,opu_session_id,id)
);
CREATE TABLE external_oocyte_receipt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    received_at timestamptz NOT NULL,
    source_reference varchar(500) NOT NULL CHECK (btrim(source_reference)<>''),
    source_farm_property_id uuid,
    farm_snapshot jsonb,
    owner_id uuid,
    collecting_professional_id uuid,
    total_received integer NOT NULL CHECK (total_received>=0),
    source_document_id uuid,
    notes varchar(2000),
    status varchar(16) NOT NULL CHECK (status='RECEIVED'),
    origin_type varchar(32) NOT NULL CHECK (origin_type='MANUAL'),
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,source_farm_property_id) REFERENCES farm_property(organization_id,id),
    FOREIGN KEY (organization_id,owner_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,collecting_professional_id) REFERENCES professional(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK ((source_farm_property_id IS NULL)=(farm_snapshot IS NULL))
);
CREATE INDEX transport_session_idx ON oocyte_transport(organization_id,source_session_id,received_at);
CREATE INDEX transport_collection_idx ON oocyte_transport_item(organization_id,collection_id);
CREATE INDEX external_receipt_date_idx ON external_oocyte_receipt(organization_id,received_at,id);
GRANT SELECT,INSERT ON oocyte_transport,oocyte_transport_item,external_oocyte_receipt TO bovina_runtime;
