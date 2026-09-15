ALTER TABLE embryo_package ADD COLUMN membership_updated_at timestamptz;
GRANT UPDATE(membership_updated_at) ON embryo_package TO bovina_runtime;
