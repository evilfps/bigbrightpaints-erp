CREATE TABLE IF NOT EXISTS raw_material_adjustments (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    reference_number VARCHAR(128) NOT NULL,
    adjustment_date DATE NOT NULL,
    reason TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    journal_entry_id BIGINT REFERENCES journal_entries(id) ON DELETE SET NULL,
    total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128),
    idempotency_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS raw_material_adjustment_lines (
    id BIGSERIAL PRIMARY KEY,
    adjustment_id BIGINT NOT NULL REFERENCES raw_material_adjustments(id) ON DELETE CASCADE,
    raw_material_id BIGINT NOT NULL REFERENCES raw_materials(id) ON DELETE CASCADE,
    quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    unit_cost NUMERIC(19,4) NOT NULL DEFAULT 0,
    amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    note TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_raw_material_adjustments_company_date
    ON raw_material_adjustments (company_id, adjustment_date DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_raw_material_adjustments_company_reference
    ON raw_material_adjustments (company_id, reference_number);

CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_material_adjustments_idempotency
    ON raw_material_adjustments (company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_material_adjustments_company_reference
    ON raw_material_adjustments (company_id, reference_number);

CREATE INDEX IF NOT EXISTS idx_raw_material_adjustment_lines_adjustment
    ON raw_material_adjustment_lines (adjustment_id, id);

CREATE INDEX IF NOT EXISTS idx_raw_material_adjustment_lines_material
    ON raw_material_adjustment_lines (raw_material_id);
