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

ALTER TABLE raw_material_batches
    ADD COLUMN IF NOT EXISTS manufactured_at TIMESTAMPTZ;

UPDATE raw_material_batches
SET manufactured_at = COALESCE(manufactured_at, received_at, NOW())
WHERE manufactured_at IS NULL;

ALTER TABLE raw_material_batches
    ALTER COLUMN manufactured_at SET NOT NULL;

ALTER TABLE raw_material_batches
    ADD COLUMN IF NOT EXISTS expiry_date DATE;

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

ALTER TABLE production_logs
    ADD COLUMN IF NOT EXISTS wastage_reason_code VARCHAR(64);

UPDATE production_logs
SET wastage_reason_code = 'PROCESS_LOSS'
WHERE wastage_reason_code IS NULL OR BTRIM(wastage_reason_code) = '';

ALTER TABLE production_logs
    ALTER COLUMN wastage_reason_code SET DEFAULT 'PROCESS_LOSS';

ALTER TABLE production_logs
    ALTER COLUMN wastage_reason_code SET NOT NULL;

ALTER TABLE production_log_materials
    ADD COLUMN IF NOT EXISTS raw_material_batch_id BIGINT;

ALTER TABLE production_log_materials
    ADD COLUMN IF NOT EXISTS raw_material_movement_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_production_log_materials_raw_material_batch'
    ) THEN
        ALTER TABLE production_log_materials
            ADD CONSTRAINT fk_production_log_materials_raw_material_batch
            FOREIGN KEY (raw_material_batch_id)
            REFERENCES raw_material_batches(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_production_log_materials_raw_material_batch_id
    ON production_log_materials(raw_material_batch_id);

CREATE INDEX IF NOT EXISTS idx_production_log_materials_raw_material_movement_id
    ON production_log_materials(raw_material_movement_id);

CREATE INDEX IF NOT EXISTS idx_inventory_movements_finished_good_batch_created_at
    ON inventory_movements(finished_good_batch_id, created_at);

CREATE INDEX IF NOT EXISTS idx_raw_material_movements_raw_material_batch_created_at
    ON raw_material_movements(raw_material_batch_id, created_at);
