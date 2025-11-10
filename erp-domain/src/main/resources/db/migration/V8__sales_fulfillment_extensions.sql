CREATE TABLE IF NOT EXISTS order_sequences (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    fiscal_year INT NOT NULL,
    next_number BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(company_id, fiscal_year)
);

ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

ALTER TABLE journal_entries
    ADD COLUMN IF NOT EXISTS dealer_id BIGINT REFERENCES dealers(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_journal_entries_dealer
    ON journal_entries (dealer_id);
