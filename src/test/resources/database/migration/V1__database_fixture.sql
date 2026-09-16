CREATE TABLE fixture_parent (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  UNIQUE (organization_id, id)
);
CREATE TABLE fixture_record (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  parent_id uuid NOT NULL,
  measured_value integer NOT NULL CHECK (measured_value >= 0),
  occurred_at timestamptz NOT NULL,
  civil_date date NOT NULL,
  local_time timestamp without time zone NOT NULL,
  zone_id varchar(80) NOT NULL,
  version bigint NOT NULL,
  FOREIGN KEY (organization_id, parent_id) REFERENCES fixture_parent (organization_id, id)
);
CREATE TABLE fixture_reservation (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  active boolean NOT NULL
);
CREATE UNIQUE INDEX one_active_reservation
  ON fixture_reservation (organization_id, resource_id) WHERE active;
CREATE TABLE fixture_identifier (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  issuer varchar(80),
  code varchar(80) NOT NULL
);
CREATE UNIQUE INDEX identifier_identity
  ON fixture_identifier (organization_id, issuer, lower(code)) NULLS NOT DISTINCT;
CREATE TABLE fixture_ledger (
  id uuid PRIMARY KEY,
  note varchar(80) NOT NULL
);
CREATE TABLE fixture_command (
  command_key uuid PRIMARY KEY,
  request_hash varchar(80) NOT NULL,
  result integer NOT NULL
);
GRANT USAGE ON SCHEMA baseline_probe TO bovina_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON fixture_parent, fixture_record,
  fixture_reservation, fixture_identifier, fixture_command TO bovina_runtime;
GRANT SELECT, INSERT ON fixture_ledger TO bovina_runtime;
