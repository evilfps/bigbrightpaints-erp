CREATE TABLE IF NOT EXISTS raw_materials (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    company_id      BIGINT      NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    sku             VARCHAR(128) NOT NULL,
    unit_type       VARCHAR(64)  NOT NULL,
    reorder_level   NUMERIC(18,4) NOT NULL DEFAULT 0,
    current_stock   NUMERIC(18,4) NOT NULL DEFAULT 0,
    min_stock       NUMERIC(18,4) NOT NULL DEFAULT 0,
    max_stock       NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE(company_id, sku)
);

CREATE TABLE IF NOT EXISTS raw_material_batches (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    raw_material_id BIGINT      NOT NULL REFERENCES raw_materials(id) ON DELETE CASCADE,
    batch_code      VARCHAR(128) NOT NULL,
    quantity        NUMERIC(18,4) NOT NULL,
    unit            VARCHAR(32)   NOT NULL,
    cost_per_unit   NUMERIC(18,4) NOT NULL,
    supplier        VARCHAR(255),
    received_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    notes           TEXT
);

CREATE TABLE IF NOT EXISTS inventory_reservations (
    id              BIGSERIAL PRIMARY KEY,
    raw_material_id BIGINT      NOT NULL REFERENCES raw_materials(id) ON DELETE CASCADE,
    reference_type  VARCHAR(64) NOT NULL,
    reference_id    VARCHAR(128) NOT NULL,
    quantity        NUMERIC(18,4) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_raw_materials_company ON raw_materials(company_id);
CREATE INDEX IF NOT EXISTS idx_raw_material_batches_material ON raw_material_batches(raw_material_id);
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_material ON inventory_reservations(raw_material_id);
