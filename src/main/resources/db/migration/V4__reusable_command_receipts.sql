-- resource_id remains the typed client reference; other results live in the replay snapshot.
ALTER TABLE idempotent_command DROP CONSTRAINT idempotent_command_check;
ALTER TABLE idempotent_command ADD CONSTRAINT command_result_shape CHECK (
    (status = 'PROCESSING' AND completed_at IS NULL AND response_status IS NULL AND response_json IS NULL AND resource_id IS NULL)
    OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND response_status IN (200,201)
        AND response_json IS NOT NULL
        AND (command_type <> 'CREATE_CLIENT_V1' OR resource_id IS NOT NULL))
);
