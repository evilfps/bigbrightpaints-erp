CREATE TABLE IF NOT EXISTS export_requests (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    report_type VARCHAR(128) NOT NULL,
    parameters TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_by VARCHAR(255),
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_export_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_export_requests_company_status_created
    ON export_requests(company_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_export_requests_company_user_created
    ON export_requests(company_id, user_id, created_at DESC);
