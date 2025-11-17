ALTER TABLE raw_materials
    ADD COLUMN IF NOT EXISTS inventory_account_id BIGINT;

