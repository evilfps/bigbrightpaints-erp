ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS payment_terms VARCHAR(16) NOT NULL DEFAULT 'NET_30';

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS bank_account_name_encrypted TEXT;

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS bank_account_number_encrypted TEXT;

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS bank_ifsc_encrypted TEXT;

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS bank_branch_encrypted TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_suppliers_status_workflow'
    ) THEN
        ALTER TABLE suppliers
            ADD CONSTRAINT chk_suppliers_status_workflow
            CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'SUSPENDED'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_suppliers_payment_terms'
    ) THEN
        ALTER TABLE suppliers
            ADD CONSTRAINT chk_suppliers_payment_terms
            CHECK (payment_terms IN ('NET_30', 'NET_60', 'NET_90'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_suppliers_company_status
    ON suppliers (company_id, status);

CREATE INDEX IF NOT EXISTS idx_suppliers_company_payment_terms
    ON suppliers (company_id, payment_terms);

UPDATE suppliers
SET status = 'SUSPENDED'
WHERE UPPER(status) IN ('INACTIVE', 'DISABLED');

UPDATE suppliers
SET status = 'PENDING'
WHERE UPPER(status) = 'NEW';

UPDATE purchase_orders
SET status = 'APPROVED'
WHERE UPPER(status) = 'OPEN';

UPDATE purchase_orders
SET status = 'PARTIALLY_RECEIVED'
WHERE UPPER(status) = 'PARTIAL';

UPDATE purchase_orders
SET status = 'VOID'
WHERE UPPER(status) IN ('CANCELLED', 'CANCELED');

UPDATE goods_receipts
SET status = 'PARTIAL'
WHERE UPPER(status) = 'PARTIALLY_RECEIVED';
