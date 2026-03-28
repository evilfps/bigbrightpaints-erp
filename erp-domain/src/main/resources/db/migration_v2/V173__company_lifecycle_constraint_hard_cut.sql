UPDATE companies
SET lifecycle_state = 'SUSPENDED'
WHERE lifecycle_state = 'HOLD';

UPDATE companies
SET lifecycle_state = 'DEACTIVATED'
WHERE lifecycle_state = 'BLOCKED';

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_lifecycle_state;

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_lifecycle_state_v167;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_lifecycle_state_v173
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'));
