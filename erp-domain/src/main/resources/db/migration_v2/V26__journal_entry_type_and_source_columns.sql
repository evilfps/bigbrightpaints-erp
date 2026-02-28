ALTER TABLE journal_entries
    ADD COLUMN IF NOT EXISTS journal_type VARCHAR(32) NOT NULL DEFAULT 'AUTOMATED',
    ADD COLUMN IF NOT EXISTS source_module VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_reference VARCHAR(128);

UPDATE journal_entries
SET journal_type = 'AUTOMATED'
WHERE journal_type IS NULL;

CREATE INDEX IF NOT EXISTS idx_journal_entries_company_type_date
    ON journal_entries (company_id, journal_type, entry_date DESC);

CREATE INDEX IF NOT EXISTS idx_journal_entries_company_source_module_date
    ON journal_entries (company_id, source_module, entry_date DESC);
