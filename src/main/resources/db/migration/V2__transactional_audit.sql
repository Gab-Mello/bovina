CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    occurred_at timestamptz NOT NULL,
    actor_id uuid NOT NULL,
    actor_type varchar(16) NOT NULL CHECK (actor_type = 'USER'),
    action varchar(32) NOT NULL,
    entity_type varchar(64) NOT NULL,
    entity_id uuid NOT NULL,
    entity_version bigint,
    reason varchar(500),
    previous_state varchar(64),
    new_state varchar(64),
    correlation_id uuid NOT NULL,
    FOREIGN KEY (organization_id, actor_id) REFERENCES organization_membership(organization_id, user_account_id)
);
CREATE INDEX audit_target_idx ON audit_event(organization_id, entity_type, entity_id, occurred_at, id);
CREATE INDEX audit_actor_idx ON audit_event(organization_id, actor_id);
GRANT SELECT, INSERT ON audit_event TO bovina_runtime;
