ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS journal_entry_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_invoices_journal_entry'
    ) THEN
        ALTER TABLE invoices
            ADD CONSTRAINT fk_invoices_journal_entry
            FOREIGN KEY (journal_entry_id)
            REFERENCES journal_entries(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_invoices_journal_entry
    ON invoices (journal_entry_id);
