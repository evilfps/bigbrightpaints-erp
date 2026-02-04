ALTER TABLE goods_receipts
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS idempotency_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS ux_goods_receipts_company_idempotency_key
    ON goods_receipts (company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
