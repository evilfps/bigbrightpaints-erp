CREATE INDEX IF NOT EXISTS idx_packaging_slips_company_order_backorder_status
    ON packaging_slips (company_id, sales_order_id, is_backorder, status);

CREATE INDEX IF NOT EXISTS idx_dealers_company_receivable_account
    ON dealers (company_id, receivable_account_id);

CREATE INDEX IF NOT EXISTS idx_suppliers_company_payable_account
    ON suppliers (company_id, payable_account_id);

CREATE INDEX IF NOT EXISTS idx_orchestrator_outbox_publishing_deadletter_last_error
    ON orchestrator_outbox (status, dead_letter, last_error);

CREATE INDEX IF NOT EXISTS idx_raw_material_batches_raw_material_id_id
    ON raw_material_batches (raw_material_id, id);
