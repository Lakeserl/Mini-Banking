CREATE TABLE IF NOT EXISTS events (
    event_id        UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    aggregate_type  VARCHAR(50) NOT NULL,
    version         BIGINT NOT NULL CHECK (version >= 1),
    event_type      VARCHAR(100) NOT NULL,
    schema_version  INT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    correlation_id  UUID NOT NULL,
    causation_id    UUID,
    payload         JSONB NOT NULL,
    CONSTRAINT uq_events_aggregate_version UNIQUE (aggregate_id, version)
);

CREATE TABLE IF NOT EXISTS snapshots (
    aggregate_id             UUID PRIMARY KEY,
    aggregate_type           VARCHAR(50) NOT NULL,
    version                  BIGINT NOT NULL,
    last_event_id            UUID NOT NULL,
    snapshot_schema_version  INT NOT NULL,
    state                    JSONB NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION events_reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'events is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_events_no_update_delete ON events;
CREATE TRIGGER trg_events_no_update_delete BEFORE UPDATE OR DELETE ON events
FOR EACH ROW EXECUTE FUNCTION events_reject_mutation();

DROP TRIGGER IF EXISTS trg_events_no_truncate ON events;
CREATE TRIGGER trg_events_no_truncate BEFORE TRUNCATE ON events
FOR EACH STATEMENT EXECUTE FUNCTION events_reject_mutation();
