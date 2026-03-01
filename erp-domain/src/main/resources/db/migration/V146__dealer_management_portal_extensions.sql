ALTER TABLE dealers
    ADD COLUMN IF NOT EXISTS payment_terms VARCHAR(16) NOT NULL DEFAULT 'NET_30';

ALTER TABLE dealers
    ADD COLUMN IF NOT EXISTS region VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_dealers_payment_terms'
    ) THEN
        ALTER TABLE dealers
            ADD CONSTRAINT chk_dealers_payment_terms
            CHECK (payment_terms IN ('NET_30', 'NET_60', 'NET_90'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dealers_company_status_region
    ON dealers (company_id, status, region);

CREATE INDEX IF NOT EXISTS idx_dealers_company_payment_terms
    ON dealers (company_id, payment_terms);
