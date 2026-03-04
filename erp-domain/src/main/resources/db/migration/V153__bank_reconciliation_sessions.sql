CREATE TABLE IF NOT EXISTS bank_reconciliation_sessions (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    bank_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    accounting_period_id BIGINT REFERENCES accounting_periods(id) ON DELETE SET NULL,
    reference_number VARCHAR(64) NOT NULL,
    statement_date DATE NOT NULL,
    statement_ending_balance NUMERIC(19,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(255) NOT NULL,
    completed_by VARCHAR(255),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_bank_recon_sessions_company_reference UNIQUE (company_id, reference_number),
    CONSTRAINT chk_bank_recon_session_status CHECK (status IN ('DRAFT', 'COMPLETED'))
);

CREATE INDEX IF NOT EXISTS idx_bank_recon_sessions_company_created
    ON bank_reconciliation_sessions(company_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_bank_recon_sessions_company_status
    ON bank_reconciliation_sessions(company_id, status, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_bank_recon_sessions_company_account
    ON bank_reconciliation_sessions(company_id, bank_account_id, statement_date DESC, id DESC);

CREATE TABLE IF NOT EXISTS bank_reconciliation_items (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    session_id BIGINT NOT NULL REFERENCES bank_reconciliation_sessions(id) ON DELETE CASCADE,
    journal_line_id BIGINT NOT NULL REFERENCES journal_lines(id) ON DELETE RESTRICT,
    reference_number VARCHAR(128),
    amount NUMERIC(19,2) NOT NULL,
    cleared_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cleared_by VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_bank_recon_item_session_line UNIQUE (session_id, journal_line_id)
);

CREATE INDEX IF NOT EXISTS idx_bank_recon_items_session
    ON bank_reconciliation_items(session_id, cleared_at ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_bank_recon_items_company_session
    ON bank_reconciliation_items(company_id, session_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_bank_recon_items_line
    ON bank_reconciliation_items(journal_line_id);
