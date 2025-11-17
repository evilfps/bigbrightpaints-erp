ALTER TABLE payroll_runs
    ADD COLUMN IF NOT EXISTS total_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS journal_entry_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_runs_journal_entry'
    ) THEN
        ALTER TABLE payroll_runs
            ADD CONSTRAINT fk_payroll_runs_journal_entry
            FOREIGN KEY (journal_entry_id)
            REFERENCES journal_entries(id)
            ON DELETE SET NULL;
    END IF;
END $$;
