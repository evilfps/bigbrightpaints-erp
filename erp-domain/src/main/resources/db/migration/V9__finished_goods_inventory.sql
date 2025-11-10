CREATE TABLE IF NOT EXISTS finished_goods (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    product_code VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    unit VARCHAR(32) NOT NULL DEFAULT 'UNIT',
    current_stock NUMERIC(18,3) NOT NULL DEFAULT 0,
    reserved_stock NUMERIC(18,3) NOT NULL DEFAULT 0,
    costing_method VARCHAR(32) NOT NULL DEFAULT 'FIFO',
    valuation_account_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(company_id, product_code)
);

CREATE TABLE IF NOT EXISTS finished_good_batches (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    finished_good_id BIGINT NOT NULL REFERENCES finished_goods(id) ON DELETE CASCADE,
    batch_code VARCHAR(128) NOT NULL,
    quantity_total NUMERIC(18,3) NOT NULL,
    quantity_available NUMERIC(18,3) NOT NULL,
    unit_cost NUMERIC(18,4) NOT NULL DEFAULT 0,
    manufactured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expiry_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(finished_good_id, batch_code)
);

CREATE TABLE IF NOT EXISTS packaging_slips (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    sales_order_id BIGINT NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
    slip_number VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at TIMESTAMPTZ,
    UNIQUE(company_id, slip_number)
);

CREATE TABLE IF NOT EXISTS packaging_slip_lines (
    id BIGSERIAL PRIMARY KEY,
    packaging_slip_id BIGINT NOT NULL REFERENCES packaging_slips(id) ON DELETE CASCADE,
    finished_good_batch_id BIGINT NOT NULL REFERENCES finished_good_batches(id) ON DELETE CASCADE,
    quantity NUMERIC(18,3) NOT NULL,
    unit_cost NUMERIC(18,4) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS inventory_movements (
    id BIGSERIAL PRIMARY KEY,
    finished_good_id BIGINT NOT NULL REFERENCES finished_goods(id) ON DELETE CASCADE,
    finished_good_batch_id BIGINT REFERENCES finished_good_batches(id) ON DELETE SET NULL,
    reference_type VARCHAR(64) NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity NUMERIC(18,3) NOT NULL,
    unit_cost NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE inventory_reservations
    ADD COLUMN IF NOT EXISTS finished_good_id BIGINT REFERENCES finished_goods(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS finished_good_batch_id BIGINT REFERENCES finished_good_batches(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS reserved_quantity NUMERIC(18,3),
    ADD COLUMN IF NOT EXISTS fulfilled_quantity NUMERIC(18,3);

CREATE INDEX IF NOT EXISTS idx_inventory_reservations_fg
    ON inventory_reservations (finished_good_id);

CREATE INDEX IF NOT EXISTS idx_packaging_slips_order
    ON packaging_slips (sales_order_id);

CREATE INDEX IF NOT EXISTS idx_inventory_movements_ref
    ON inventory_movements (reference_type, reference_id);
