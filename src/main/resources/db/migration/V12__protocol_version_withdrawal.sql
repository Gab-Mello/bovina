-- Withdrawing applicability never modifies published content or historical references.
CREATE TABLE protocol_version_withdrawal (
    organization_id uuid NOT NULL,
    version_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    withdrawn_by uuid NOT NULL,
    withdrawn_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id,version_id),
    FOREIGN KEY (organization_id,version_id) REFERENCES protocol_version(organization_id,id),
    FOREIGN KEY (organization_id,withdrawn_by) REFERENCES organization_membership(organization_id,user_account_id)
);
GRANT SELECT,INSERT ON protocol_version_withdrawal TO bovina_runtime;
