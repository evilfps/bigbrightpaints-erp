ALTER TABLE accounting_periods
    ADD COLUMN IF NOT EXISTS costing_method VARCHAR(32) NOT NULL DEFAULT 'WEIGHTED_AVERAGE';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_accounting_periods_costing_method'
    ) THEN
        ALTER TABLE accounting_periods
            ADD CONSTRAINT chk_accounting_periods_costing_method
            CHECK (costing_method IN ('FIFO', 'LIFO', 'WEIGHTED_AVERAGE'));
    END IF;
END $$;
