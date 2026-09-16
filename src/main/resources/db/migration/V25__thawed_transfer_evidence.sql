ALTER TABLE thaw_event
    ADD COLUMN protocol_version_id uuid,
    ADD CONSTRAINT thaw_event_protocol_version_fk
        FOREIGN KEY (organization_id,protocol_version_id) REFERENCES protocol_version(organization_id,id);

ALTER TABLE embryo_transfer
    ADD COLUMN thaw_event_id uuid,
    ADD CONSTRAINT embryo_transfer_thaw_origin_ck
        CHECK ((transfer_origin='FRESH' AND thaw_event_id IS NULL)
            OR (transfer_origin='THAWED' AND thaw_event_id IS NOT NULL)),
    ADD CONSTRAINT embryo_transfer_thaw_item_fk
        FOREIGN KEY (organization_id,thaw_event_id,embryo_id)
        REFERENCES thaw_event_item(organization_id,thaw_event_id,embryo_id);
CREATE UNIQUE INDEX embryo_transfer_thaw_item_uk
    ON embryo_transfer(organization_id,thaw_event_id,embryo_id)
    WHERE thaw_event_id IS NOT NULL;
