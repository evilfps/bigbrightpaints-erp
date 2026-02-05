-- Catalog import idempotency tracking.

CREATE TABLE IF NOT EXISTS catalog_imports (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    idempotency_hash VARCHAR(64),
    file_hash VARCHAR(64),
    file_name VARCHAR(256),
    rows_processed INTEGER NOT NULL DEFAULT 0,
    brands_created INTEGER NOT NULL DEFAULT 0,
    products_created INTEGER NOT NULL DEFAULT 0,
    products_updated INTEGER NOT NULL DEFAULT 0,
    raw_materials_seeded INTEGER NOT NULL DEFAULT 0,
    errors_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(company_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_catalog_imports_company
    ON catalog_imports(company_id);
