CREATE TABLE establishment_capability (
    organization_id uuid NOT NULL,
    establishment_id uuid NOT NULL,
    capability varchar(32) NOT NULL CHECK (capability IN ('OOCYTE_COLLECTION','EMBRYO_PRODUCTION','CRYOSTORAGE','TRANSFER')),
    PRIMARY KEY (organization_id,establishment_id,capability),
    FOREIGN KEY (organization_id,establishment_id) REFERENCES establishment(organization_id,id)
);
ALTER TABLE establishment ADD COLUMN registration_valid_from date;
ALTER TABLE establishment ADD COLUMN registration_valid_until date;
ALTER TABLE establishment ADD CONSTRAINT establishment_registration_period CHECK (
    registration_valid_until IS NULL OR (registration_valid_from IS NOT NULL AND registration_valid_until > registration_valid_from));
GRANT SELECT,INSERT ON establishment_capability TO bovina_runtime;
