ALTER TABLE journal_entries
    ADD COLUMN IF NOT EXISTS attachment_references TEXT;

CREATE TABLE IF NOT EXISTS closed_period_posting_exceptions (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    accounting_period_id BIGINT NOT NULL REFERENCES accounting_periods(id) ON DELETE CASCADE,
    document_type VARCHAR(64) NOT NULL,
    document_reference VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL,
    approved_by VARCHAR(255) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_by VARCHAR(255),
    used_at TIMESTAMPTZ,
    journal_entry_id BIGINT REFERENCES journal_entries(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_closed_period_posting_exceptions_company_public
    ON closed_period_posting_exceptions (company_id, public_id);

CREATE INDEX IF NOT EXISTS idx_closed_period_posting_exceptions_company_document
    ON closed_period_posting_exceptions (company_id, document_type, document_reference, approved_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_closed_period_posting_exceptions_company_expiry
    ON closed_period_posting_exceptions (company_id, expires_at DESC, id DESC);
