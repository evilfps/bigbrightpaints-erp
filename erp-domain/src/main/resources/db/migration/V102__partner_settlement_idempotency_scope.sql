-- Allow multi-allocation settlements to share the same idempotency key
DROP INDEX IF EXISTS idx_partner_settlement_idempotency;

CREATE INDEX IF NOT EXISTS idx_partner_settlement_idempotency
    ON partner_settlement_allocations(company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_partner_settlement_idem_invoice
    ON partner_settlement_allocations(company_id, idempotency_key, invoice_id)
    WHERE idempotency_key IS NOT NULL AND invoice_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_partner_settlement_idem_purchase
    ON partner_settlement_allocations(company_id, idempotency_key, purchase_id)
    WHERE idempotency_key IS NOT NULL AND purchase_id IS NOT NULL;
