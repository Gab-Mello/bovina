ALTER TABLE party_role DROP CONSTRAINT party_role_role_check;
ALTER TABLE party_role ADD CONSTRAINT party_role_role_check CHECK
    (role IN ('CLIENT','ANIMAL_OWNER','MATERIAL_OWNER','SHIPMENT_DESTINATION','SUPPLIER','SEMEN_PRODUCER_LEGAL_ENTITY'));
ALTER TABLE party ADD COLUMN legal_name varchar(200);
ALTER TABLE party ADD COLUMN address_line varchar(250);
ALTER TABLE party ADD COLUMN municipality varchar(120);
ALTER TABLE party ADD COLUMN state varchar(80);
ALTER TABLE party ADD COLUMN country varchar(2);
ALTER TABLE party ADD COLUMN postal_code varchar(32);
ALTER TABLE party ADD CONSTRAINT party_address_shape CHECK (
    (address_line IS NULL AND municipality IS NULL AND state IS NULL AND country IS NULL AND postal_code IS NULL)
    OR (address_line IS NOT NULL AND municipality IS NOT NULL AND state IS NOT NULL AND country IS NOT NULL));
CREATE INDEX party_name_idx ON party(organization_id,lower(display_name),id);
CREATE INDEX party_role_lookup_idx ON party_role(organization_id,role,party_id);

CREATE TABLE party_identifier (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    party_id uuid NOT NULL,
    type varchar(40) NOT NULL,
    issuer varchar(120),
    value varchar(160) NOT NULL,
    normalized_value varchar(160) NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL,
    FOREIGN KEY (organization_id,party_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id),
    UNIQUE NULLS NOT DISTINCT (organization_id,party_id,type,issuer,normalized_value)
);
CREATE INDEX party_identifier_search_idx ON party_identifier(organization_id,type,normalized_value);

CREATE TABLE farm_property (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    owner_id uuid,
    operator_id uuid,
    address_line varchar(250) NOT NULL,
    municipality varchar(120) NOT NULL,
    state varchar(80) NOT NULL,
    country varchar(2) NOT NULL,
    postal_code varchar(32),
    municipality_code varchar(40),
    internal_code varchar(80),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    version bigint NOT NULL DEFAULT 0,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,owner_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,operator_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,recorded_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX property_name_idx ON farm_property(organization_id,lower(name),id);
CREATE INDEX property_owner_idx ON farm_property(organization_id,owner_id);
CREATE INDEX property_operator_idx ON farm_property(organization_id,operator_id);
GRANT SELECT, INSERT ON party_identifier TO bovina_runtime;
GRANT SELECT, INSERT, UPDATE ON farm_property TO bovina_runtime;
