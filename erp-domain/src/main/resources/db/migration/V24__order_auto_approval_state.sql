CREATE TABLE IF NOT EXISTS order_auto_approval_state (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    inventory_reserved BOOLEAN NOT NULL DEFAULT FALSE,
    sales_journal_posted BOOLEAN NOT NULL DEFAULT FALSE,
    dispatch_finalized BOOLEAN NOT NULL DEFAULT FALSE,
    invoice_issued BOOLEAN NOT NULL DEFAULT FALSE,
    order_status_updated BOOLEAN NOT NULL DEFAULT FALSE,
    last_error VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_order_auto_approval_company_order UNIQUE (company_code, order_id)
);
