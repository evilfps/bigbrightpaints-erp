ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS enabled_modules JSONB;

UPDATE companies
SET enabled_modules = '["MANUFACTURING","HR_PAYROLL","PURCHASING","PORTAL","REPORTS_ADVANCED"]'::jsonb
WHERE enabled_modules IS NULL;

ALTER TABLE companies
    ALTER COLUMN enabled_modules SET DEFAULT '["MANUFACTURING","HR_PAYROLL","PURCHASING","PORTAL","REPORTS_ADVANCED"]'::jsonb;

ALTER TABLE companies
    ALTER COLUMN enabled_modules SET NOT NULL;

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
