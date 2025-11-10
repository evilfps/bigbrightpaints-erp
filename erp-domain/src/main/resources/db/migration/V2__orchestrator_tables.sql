CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS orchestrator_outbox (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(128)    NOT NULL,
    aggregate_id    VARCHAR(128)    NOT NULL,
    event_type      VARCHAR(128)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orchestrator_outbox_status_created
    ON orchestrator_outbox (status, created_at);

CREATE TABLE IF NOT EXISTS orchestrator_audit (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id    VARCHAR(128)    NOT NULL,
    event_type  VARCHAR(128)    NOT NULL,
    timestamp   TIMESTAMPTZ     NOT NULL,
    details     TEXT            NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_orchestrator_audit_trace
    ON orchestrator_audit (trace_id, timestamp);

CREATE TABLE IF NOT EXISTS scheduled_jobs (
    job_id          VARCHAR(128)    PRIMARY KEY,
    cron_expression VARCHAR(64)     NOT NULL,
    description     VARCHAR(255),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMPTZ,
    next_run_at     TIMESTAMPTZ,
    owner           VARCHAR(128)
);
