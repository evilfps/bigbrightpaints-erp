ALTER TABLE inventory_movements
    ADD COLUMN IF NOT EXISTS packing_slip_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_inventory_movements_packing_slip_id
    ON inventory_movements (packing_slip_id);
