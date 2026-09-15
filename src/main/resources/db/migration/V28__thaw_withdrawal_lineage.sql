ALTER TABLE inventory_movement ADD CONSTRAINT inventory_movement_package_ref_uk
    UNIQUE (organization_id,id,package_id);
ALTER TABLE thaw_event
    ADD COLUMN withdrawal_movement_id uuid NOT NULL,
    ADD CONSTRAINT thaw_event_withdrawal_fk
        FOREIGN KEY (organization_id,withdrawal_movement_id,package_id)
        REFERENCES inventory_movement(organization_id,id,package_id);
