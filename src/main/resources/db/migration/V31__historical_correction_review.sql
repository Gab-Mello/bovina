-- Finalized facts remain unchanged. Correction review has its own immutable history.
ALTER TABLE record_correction DROP CONSTRAINT record_correction_organization_id_subject_id_fkey;
ALTER TABLE record_correction
    ADD COLUMN mating_id uuid,
    ADD COLUMN cryopreservation_item_id uuid,
    ADD COLUMN package_item_id uuid,
    ADD COLUMN transfer_id uuid,
    ADD COLUMN pregnancy_check_id uuid,
    ADD COLUMN previous_semantics jsonb,
    ADD COLUMN subject_version bigint CHECK (subject_version >= 0),
    ADD COLUMN source_document_id uuid;
UPDATE record_correction SET mating_id=subject_id WHERE subject_type='MATING';
ALTER TABLE record_correction ADD CONSTRAINT correction_subject_identity CHECK (
    num_nonnulls(mating_id,cryopreservation_item_id,package_item_id,transfer_id,pregnancy_check_id)=1
    AND coalesce(((subject_type='MATING' AND subject_id=mating_id)
      OR (subject_type='CRYOPRESERVATION_ITEM' AND subject_id=cryopreservation_item_id)
      OR (subject_type='PACKAGE_ITEM' AND subject_id=package_item_id)
      OR (subject_type='EMBRYO_TRANSFER' AND subject_id=transfer_id)
      OR (subject_type='PREGNANCY_CHECK' AND subject_id=pregnancy_check_id)),false));
ALTER TABLE record_correction
    ADD FOREIGN KEY (organization_id,mating_id) REFERENCES mating(organization_id,id),
    ADD FOREIGN KEY (organization_id,cryopreservation_item_id) REFERENCES cryopreservation_item(organization_id,id),
    ADD FOREIGN KEY (organization_id,package_item_id) REFERENCES package_item(organization_id,id),
    ADD FOREIGN KEY (organization_id,transfer_id) REFERENCES embryo_transfer(organization_id,id),
    ADD FOREIGN KEY (organization_id,pregnancy_check_id) REFERENCES pregnancy_check(organization_id,id),
    ADD FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id);

CREATE TABLE record_correction_review (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    correction_id uuid NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision='REJECTED'),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    reviewed_by uuid NOT NULL,
    reviewed_at timestamptz NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,correction_id),
    FOREIGN KEY (organization_id,correction_id) REFERENCES record_correction(organization_id,id),
    FOREIGN KEY (organization_id,reviewed_by) REFERENCES organization_membership(organization_id,user_account_id)
);
-- Approval/apply are deliberately not enabled before approval and evidence policy validation.
GRANT SELECT,INSERT ON record_correction_review TO bovina_runtime;
GRANT UPDATE(id) ON record_correction TO bovina_runtime;
