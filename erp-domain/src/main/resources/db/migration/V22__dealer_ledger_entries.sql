CREATE TABLE IF NOT EXISTS dealer_ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    dealer_id BIGINT NOT NULL REFERENCES dealers(id) ON DELETE CASCADE,
    journal_entry_id BIGINT REFERENCES journal_entries(id) ON DELETE SET NULL,
    entry_date DATE NOT NULL,
    reference_number VARCHAR(64) NOT NULL,
    memo TEXT,
    debit NUMERIC(18,2) NOT NULL DEFAULT 0,
    credit NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dealer_ledger_company
    ON dealer_ledger_entries (company_id);

CREATE INDEX IF NOT EXISTS idx_dealer_ledger_dealer
    ON dealer_ledger_entries (dealer_id);
