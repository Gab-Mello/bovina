ALTER TABLE package_item ADD CONSTRAINT package_item_package_embryo_ref_uk
    UNIQUE (organization_id,id,embryo_id,package_id);
ALTER TABLE thaw_event_item DROP CONSTRAINT thaw_event_item_organization_id_package_item_id_embryo_id_fkey;
ALTER TABLE thaw_event_item ADD CONSTRAINT thaw_item_package_membership_fk
    FOREIGN KEY (organization_id,package_item_id,embryo_id,package_id)
    REFERENCES package_item(organization_id,id,embryo_id,package_id);
