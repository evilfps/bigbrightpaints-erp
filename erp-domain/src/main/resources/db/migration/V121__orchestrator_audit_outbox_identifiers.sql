-- Add request/trace/idempotency identifiers to orchestrator audit/outbox for queryable debugging.

ALTER TABLE orchestrator_outbox
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_orchestrator_outbox_company_trace
    ON orchestrator_outbox (company_id, trace_id);

CREATE INDEX IF NOT EXISTS idx_orchestrator_outbox_company_request
    ON orchestrator_outbox (company_id, request_id);

CREATE INDEX IF NOT EXISTS idx_orchestrator_outbox_company_idem
    ON orchestrator_outbox (company_id, idempotency_key);

ALTER TABLE orchestrator_audit
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_orchestrator_audit_company_request
    ON orchestrator_audit (company_id, request_id, "timestamp");

CREATE INDEX IF NOT EXISTS idx_orchestrator_audit_company_idem
    ON orchestrator_audit (company_id, idempotency_key, "timestamp");
