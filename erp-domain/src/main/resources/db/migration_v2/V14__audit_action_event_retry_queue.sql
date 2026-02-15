CREATE TABLE audit_action_event_retries (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    failed_attempt_count INTEGER NOT NULL DEFAULT 1,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_audit_action_event_retries_due
    ON audit_action_event_retries (next_attempt_at, id);

CREATE INDEX idx_audit_action_event_retries_company_due
    ON audit_action_event_retries (company_id, next_attempt_at, id);
