CREATE TABLE IF NOT EXISTS sales_order_status_history (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    sales_order_id BIGINT NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    reason TEXT,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sales_order_status_history_order_changed_at
    ON sales_order_status_history (sales_order_id, changed_at ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_sales_order_status_history_company_order
    ON sales_order_status_history (company_id, sales_order_id);

ALTER TABLE raw_material_batches
    ADD COLUMN IF NOT EXISTS expiry_date DATE;
