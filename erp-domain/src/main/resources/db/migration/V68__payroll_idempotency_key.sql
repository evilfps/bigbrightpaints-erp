-- Add idempotency_key column to payroll_runs for duplicate prevention
ALTER TABLE payroll_runs ADD COLUMN idempotency_key VARCHAR(255);

-- Create unique constraint per company to prevent duplicate payroll runs
CREATE UNIQUE INDEX uk_payroll_runs_company_idempotency
    ON payroll_runs(company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
