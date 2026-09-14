ALTER TABLE embryo
    ADD COLUMN confirmed_by uuid,
    ADD COLUMN confirmed_at timestamptz,
    ADD COLUMN derivation_reference varchar(256),
    ADD CONSTRAINT embryo_confirmed_by_fk
        FOREIGN KEY (organization_id,confirmed_by)
        REFERENCES organization_membership(organization_id,user_account_id),
    ADD CONSTRAINT embryo_confirmation_pair_check
        CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL));
