ALTER TABLE purchase_orders
    ALTER COLUMN status SET DEFAULT 'DRAFT';

UPDATE purchase_orders
SET status = 'DRAFT'
WHERE UPPER(status) = 'OPEN';

CREATE TABLE IF NOT EXISTS purchase_order_status_history (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    reason TEXT,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_purchase_order_status_history_order_changed_at
    ON purchase_order_status_history (purchase_order_id, changed_at ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_purchase_order_status_history_company_order
    ON purchase_order_status_history (company_id, purchase_order_id);
