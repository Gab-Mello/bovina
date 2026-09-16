\getenv migration_password BOVINA_MIGRATION_PASSWORD
\getenv runtime_password BOVINA_RUNTIME_PASSWORD

CREATE ROLE bovina_migration LOGIN PASSWORD :'migration_password';
CREATE ROLE bovina_runtime LOGIN PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO bovina_migration;
GRANT USAGE ON SCHEMA public TO bovina_runtime;
