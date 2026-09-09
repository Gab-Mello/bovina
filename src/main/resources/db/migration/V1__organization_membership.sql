CREATE TABLE user_account (
    id uuid PRIMARY KEY,
    issuer varchar(512) NOT NULL CHECK (btrim(issuer) <> ''),
    subject varchar(255) NOT NULL CHECK (btrim(subject) <> ''),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (issuer, subject)
);

CREATE TABLE organization (
    id uuid PRIMARY KEY,
    legal_name varchar(200) NOT NULL CHECK (btrim(legal_name) <> ''),
    trade_name varchar(200),
    tax_id varchar(32) NOT NULL CHECK (btrim(tax_id) <> ''),
    timezone varchar(64) NOT NULL,
    default_locale varchar(35) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_account(id),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX organization_created_by_idx ON organization(created_by);

CREATE TABLE organization_membership (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    user_account_id uuid NOT NULL REFERENCES user_account(id),
    role varchar(24) NOT NULL CHECK (role IN ('ORG_ADMIN', 'OPERATOR', 'READ_ONLY')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, user_account_id),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX membership_user_idx ON organization_membership(user_account_id, organization_id);

GRANT SELECT, INSERT, UPDATE ON user_account, organization, organization_membership TO bovina_runtime;
