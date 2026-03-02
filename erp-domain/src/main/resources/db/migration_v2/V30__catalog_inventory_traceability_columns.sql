ALTER TABLE production_brands
    ADD COLUMN IF NOT EXISTS logo_url VARCHAR(1024);

ALTER TABLE production_brands
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE production_products
    ADD COLUMN IF NOT EXISTS hsn_code VARCHAR(32);

CREATE TABLE IF NOT EXISTS production_product_colors (
    product_id BIGINT NOT NULL REFERENCES production_products(id) ON DELETE CASCADE,
    color VARCHAR(128) NOT NULL,
    PRIMARY KEY (product_id, color)
);

CREATE TABLE IF NOT EXISTS production_product_sizes (
    product_id BIGINT NOT NULL REFERENCES production_products(id) ON DELETE CASCADE,
    size_label VARCHAR(64) NOT NULL,
    PRIMARY KEY (product_id, size_label)
);

CREATE TABLE IF NOT EXISTS production_product_carton_sizes (
    product_id BIGINT NOT NULL REFERENCES production_products(id) ON DELETE CASCADE,
    size_label VARCHAR(64) NOT NULL,
    pieces_per_carton INTEGER NOT NULL,
    PRIMARY KEY (product_id, size_label),
    CONSTRAINT chk_production_product_carton_sizes_positive CHECK (pieces_per_carton > 0)
);

CREATE INDEX IF NOT EXISTS idx_production_products_company_brand_active
    ON production_products(company_id, brand_id, is_active);

CREATE INDEX IF NOT EXISTS idx_production_product_colors_lower
    ON production_product_colors(LOWER(color));

ALTER TABLE finished_good_batches
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);

UPDATE finished_good_batches
SET source = 'PRODUCTION'
WHERE source IS NULL;

ALTER TABLE finished_good_batches
    ALTER COLUMN source SET DEFAULT 'PRODUCTION';

ALTER TABLE finished_good_batches
    ALTER COLUMN source SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_finished_good_batches_source'
    ) THEN
        ALTER TABLE finished_good_batches
            ADD CONSTRAINT chk_finished_good_batches_source
            CHECK (source IN ('PRODUCTION', 'PURCHASE', 'ADJUSTMENT'));
    END IF;
END $$;

ALTER TABLE finished_goods
    ADD COLUMN IF NOT EXISTS low_stock_threshold NUMERIC(19,4);

UPDATE finished_goods
SET low_stock_threshold = 100
WHERE low_stock_threshold IS NULL;

ALTER TABLE finished_goods
    ALTER COLUMN low_stock_threshold SET DEFAULT 100;

ALTER TABLE finished_goods
    ALTER COLUMN low_stock_threshold SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_finished_goods_low_stock_threshold'
    ) THEN
        ALTER TABLE finished_goods
            ADD CONSTRAINT chk_finished_goods_low_stock_threshold
            CHECK (low_stock_threshold >= 0);
    END IF;
END $$;

ALTER TABLE raw_material_batches
    ADD COLUMN IF NOT EXISTS manufactured_at TIMESTAMPTZ;

UPDATE raw_material_batches
SET manufactured_at = COALESCE(received_at, NOW())
WHERE manufactured_at IS NULL;

ALTER TABLE raw_material_batches
    ALTER COLUMN manufactured_at SET DEFAULT NOW();

ALTER TABLE raw_material_batches
    ALTER COLUMN manufactured_at SET NOT NULL;

ALTER TABLE raw_material_batches
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);

UPDATE raw_material_batches
SET source = 'PURCHASE'
WHERE source IS NULL;

ALTER TABLE raw_material_batches
    ALTER COLUMN source SET DEFAULT 'PURCHASE';

ALTER TABLE raw_material_batches
    ALTER COLUMN source SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_raw_material_batches_source'
    ) THEN
        ALTER TABLE raw_material_batches
            ADD CONSTRAINT chk_raw_material_batches_source
            CHECK (source IN ('PRODUCTION', 'PURCHASE', 'ADJUSTMENT'));
    END IF;
END $$;
