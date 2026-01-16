ALTER TABLE payroll_runs
    DROP CONSTRAINT IF EXISTS payroll_runs_idempotency_key_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_payroll_runs_company_idempotency
    ON payroll_runs(company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

DROP INDEX IF EXISTS idx_partner_settlement_idempotency;

CREATE INDEX IF NOT EXISTS idx_partner_settlement_idempotency_lookup
    ON partner_settlement_allocations(company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_partner_settlement_idempotency_invoice
    ON partner_settlement_allocations(company_id, idempotency_key, invoice_id)
    WHERE idempotency_key IS NOT NULL AND invoice_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_partner_settlement_idempotency_purchase
    ON partner_settlement_allocations(company_id, idempotency_key, purchase_id)
    WHERE idempotency_key IS NOT NULL AND purchase_id IS NOT NULL;
