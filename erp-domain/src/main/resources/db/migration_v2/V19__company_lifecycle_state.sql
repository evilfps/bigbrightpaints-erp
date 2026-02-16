ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS lifecycle_reason VARCHAR(1024);

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_lifecycle_state;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_lifecycle_state
        CHECK (lifecycle_state IN ('ACTIVE', 'HOLD', 'BLOCKED'));
