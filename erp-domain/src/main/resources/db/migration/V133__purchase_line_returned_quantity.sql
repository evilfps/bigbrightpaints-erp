ALTER TABLE raw_material_purchase_items
    ADD COLUMN IF NOT EXISTS returned_quantity NUMERIC(18,4) NOT NULL DEFAULT 0;
