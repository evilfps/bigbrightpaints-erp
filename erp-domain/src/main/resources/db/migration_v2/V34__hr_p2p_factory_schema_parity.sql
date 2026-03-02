ALTER TABLE payroll_run_lines
    ADD COLUMN IF NOT EXISTS basic_salary_component NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS hra_component NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS da_component NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_allowance_component NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS esi_deduction NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS professional_tax_deduction NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS loan_deduction NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS leave_without_pay_deduction NUMERIC(19,2) NOT NULL DEFAULT 0;

UPDATE payroll_run_lines
SET loan_deduction = COALESCE(advance_deduction, 0)
WHERE COALESCE(loan_deduction, 0) = 0
  AND COALESCE(advance_deduction, 0) > 0;

CREATE TABLE IF NOT EXISTS purchase_order_status_history (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    reason TEXT,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_purchase_order_status_history_order_changed_at
    ON purchase_order_status_history (purchase_order_id, changed_at ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_purchase_order_status_history_company_order
    ON purchase_order_status_history (company_id, purchase_order_id);

ALTER TABLE production_log_materials
    ADD COLUMN IF NOT EXISTS raw_material_batch_id BIGINT,
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
