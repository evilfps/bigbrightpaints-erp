ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS idempotency_hash VARCHAR(64);

ALTER TABLE payroll_runs
    ADD COLUMN IF NOT EXISTS idempotency_hash VARCHAR(64);
