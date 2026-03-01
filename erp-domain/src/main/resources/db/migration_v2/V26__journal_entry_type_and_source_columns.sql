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

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS state_code VARCHAR(2);

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS gst_number VARCHAR(15),
    ADD COLUMN IF NOT EXISTS state_code VARCHAR(2),
    ADD COLUMN IF NOT EXISTS gst_registration_type VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED';

ALTER TABLE dealers
    ADD COLUMN IF NOT EXISTS gst_number VARCHAR(15),
    ADD COLUMN IF NOT EXISTS state_code VARCHAR(2),
    ADD COLUMN IF NOT EXISTS gst_registration_type VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED',
    ADD COLUMN IF NOT EXISTS payment_terms VARCHAR(16) NOT NULL DEFAULT 'NET_30',
    ADD COLUMN IF NOT EXISTS region VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_dealers_payment_terms'
    ) THEN
        ALTER TABLE dealers
            ADD CONSTRAINT chk_dealers_payment_terms
            CHECK (payment_terms IN ('NET_30', 'NET_60', 'NET_90'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dealers_company_status_region
    ON dealers (company_id, status, region);

CREATE INDEX IF NOT EXISTS idx_dealers_company_payment_terms
    ON dealers (company_id, payment_terms);
