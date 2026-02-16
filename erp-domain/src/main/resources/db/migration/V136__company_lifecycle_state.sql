ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(32);

UPDATE companies
SET lifecycle_state = 'ACTIVE'
WHERE lifecycle_state IS NULL;

ALTER TABLE companies
    ALTER COLUMN lifecycle_state SET DEFAULT 'ACTIVE';

ALTER TABLE companies
    ALTER COLUMN lifecycle_state SET NOT NULL;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS lifecycle_reason VARCHAR(1024);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_companies_lifecycle_state'
    ) THEN
        ALTER TABLE companies
            ADD CONSTRAINT chk_companies_lifecycle_state
            CHECK (lifecycle_state IN ('ACTIVE', 'HOLD', 'BLOCKED'));
    END IF;
END
$$;
