ALTER TABLE inventory_adjustments
    ADD COLUMN IF NOT EXISTS reversal_of_adjustment_id BIGINT REFERENCES inventory_adjustments(id);

CREATE INDEX IF NOT EXISTS idx_inventory_adjustments_reversal_of
    ON inventory_adjustments(reversal_of_adjustment_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_adjustments_company_reversal_of
    ON inventory_adjustments(company_id, reversal_of_adjustment_id)
    WHERE reversal_of_adjustment_id IS NOT NULL;
