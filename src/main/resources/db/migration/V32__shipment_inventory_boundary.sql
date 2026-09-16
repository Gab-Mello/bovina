CREATE TABLE shipment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    establishment_id uuid NOT NULL,
    destination_recipient_id uuid NOT NULL,
    destination_property_id uuid,
    purpose varchar(80) NOT NULL CHECK (purpose ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT','SHIPPED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id),
    FOREIGN KEY (organization_id,destination_recipient_id) REFERENCES party(organization_id,id),
    FOREIGN KEY (organization_id,destination_property_id) REFERENCES farm_property(organization_id,id),
    FOREIGN KEY (organization_id,created_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE INDEX shipment_tenant_created_idx ON shipment(organization_id,created_at DESC,id);
CREATE TABLE shipment_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    package_id uuid NOT NULL,
    expected_package_version bigint NOT NULL CHECK (expected_package_version >= 0),
    expected_location_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,id,package_id),
    UNIQUE (organization_id,shipment_id,package_id),
    FOREIGN KEY (organization_id,shipment_id) REFERENCES shipment(organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,expected_location_id) REFERENCES storage_location(organization_id,id)
);
CREATE INDEX shipment_item_package_idx ON shipment_item(organization_id,package_id,shipment_id);
CREATE TABLE inventory_shipment_reservation (
    organization_id uuid NOT NULL,
    shipment_item_id uuid NOT NULL,
    package_id uuid NOT NULL,
    reserved_at timestamptz NOT NULL,
    released_at timestamptz,
    release_reason varchar(16) CHECK (release_reason IN ('DISPATCHED','CANCELLED')),
    PRIMARY KEY (organization_id,shipment_item_id),
    FOREIGN KEY (organization_id,shipment_item_id,package_id) REFERENCES shipment_item(organization_id,id,package_id),
    CHECK ((released_at IS NULL) = (release_reason IS NULL))
);
CREATE UNIQUE INDEX inventory_active_shipment_reservation_uk
    ON inventory_shipment_reservation(organization_id,package_id) WHERE released_at IS NULL;
ALTER TABLE inventory_movement ADD COLUMN shipment_item_id uuid;
ALTER TABLE inventory_movement ADD CONSTRAINT movement_shipment_package_fk
    FOREIGN KEY (organization_id,shipment_item_id,package_id) REFERENCES shipment_item(organization_id,id,package_id);
ALTER TABLE inventory_movement ADD CONSTRAINT distribution_movement_requires_item CHECK
    ((movement_type IN ('SHIP','RETURN')) = (shipment_item_id IS NOT NULL));
CREATE UNIQUE INDEX inventory_distribution_item_movement_uk
    ON inventory_movement(organization_id,shipment_item_id,movement_type) WHERE movement_type IN ('SHIP','RETURN');
GRANT SELECT,INSERT,UPDATE(status,version) ON shipment TO bovina_runtime;
GRANT SELECT,INSERT ON shipment_item,inventory_shipment_reservation TO bovina_runtime;
GRANT UPDATE(released_at,release_reason) ON inventory_shipment_reservation TO bovina_runtime;
