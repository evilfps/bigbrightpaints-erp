ALTER TABLE bank_reconciliation_items
    ADD COLUMN IF NOT EXISTS bank_item_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_recon_item_session_bank_item
    ON bank_reconciliation_items(session_id, bank_item_id)
    WHERE bank_item_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bank_recon_items_session_bank_item
    ON bank_reconciliation_items(session_id, bank_item_id);
