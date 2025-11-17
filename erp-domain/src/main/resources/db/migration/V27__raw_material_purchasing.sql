CREATE TABLE IF NOT EXISTS suppliers (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    email VARCHAR(255),
    phone VARCHAR(64),
    address TEXT,
    credit_limit NUMERIC(18,2) NOT NULL DEFAULT 0,
    outstanding_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
    payable_account_id BIGINT REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(company_id, code)
);

CREATE INDEX IF NOT EXISTS idx_suppliers_company ON suppliers(company_id);

CREATE TABLE IF NOT EXISTS supplier_ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    supplier_id BIGINT NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    journal_entry_id BIGINT REFERENCES journal_entries(id) ON DELETE SET NULL,
    entry_date DATE NOT NULL,
    reference_number VARCHAR(128) NOT NULL,
    memo TEXT,
    debit NUMERIC(18,2) NOT NULL DEFAULT 0,
    credit NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_supplier_ledger_company ON supplier_ledger_entries(company_id);
CREATE INDEX IF NOT EXISTS idx_supplier_ledger_supplier ON supplier_ledger_entries(supplier_id);

ALTER TABLE raw_material_batches
    ADD COLUMN IF NOT EXISTS supplier_id BIGINT REFERENCES suppliers(id) ON DELETE SET NULL;

ALTER TABLE journal_entries
    ADD COLUMN IF NOT EXISTS supplier_id BIGINT REFERENCES suppliers(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_journal_entries_supplier ON journal_entries(supplier_id);

CREATE TABLE IF NOT EXISTS raw_material_movements (
    id BIGSERIAL PRIMARY KEY,
    raw_material_id BIGINT NOT NULL REFERENCES raw_materials(id) ON DELETE CASCADE,
    raw_material_batch_id BIGINT REFERENCES raw_material_batches(id) ON DELETE SET NULL,
    reference_type VARCHAR(64) NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity NUMERIC(18,4) NOT NULL,
    unit_cost NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    journal_entry_id BIGINT REFERENCES journal_entries(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_raw_material_movements_material ON raw_material_movements(raw_material_id);

CREATE TABLE IF NOT EXISTS raw_material_purchases (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    supplier_id BIGINT NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    invoice_number VARCHAR(128) NOT NULL,
    invoice_date DATE NOT NULL,
    total_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'POSTED',
    memo TEXT,
    journal_entry_id BIGINT REFERENCES journal_entries(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(company_id, invoice_number)
);

CREATE TABLE IF NOT EXISTS raw_material_purchase_items (
    id BIGSERIAL PRIMARY KEY,
    purchase_id BIGINT NOT NULL REFERENCES raw_material_purchases(id) ON DELETE CASCADE,
    raw_material_id BIGINT NOT NULL REFERENCES raw_materials(id) ON DELETE RESTRICT,
    raw_material_batch_id BIGINT REFERENCES raw_material_batches(id) ON DELETE SET NULL,
    batch_code VARCHAR(128) NOT NULL,
    quantity NUMERIC(18,4) NOT NULL,
    unit VARCHAR(64) NOT NULL,
    cost_per_unit NUMERIC(18,4) NOT NULL,
    line_total NUMERIC(18,4) NOT NULL,
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_raw_material_purchase_items_purchase ON raw_material_purchase_items(purchase_id);
