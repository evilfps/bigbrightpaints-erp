ALTER TABLE payroll_runs
    ADD COLUMN IF NOT EXISTS payment_date DATE;

ALTER TABLE production_logs
    ADD COLUMN IF NOT EXISTS wastage_reason_code VARCHAR(64);

UPDATE production_logs
SET wastage_reason_code = 'PROCESS_LOSS'
WHERE wastage_reason_code IS NULL OR BTRIM(wastage_reason_code) = '';

ALTER TABLE production_logs
    ALTER COLUMN wastage_reason_code SET DEFAULT 'PROCESS_LOSS';

ALTER TABLE production_logs
    ALTER COLUMN wastage_reason_code SET NOT NULL;
