ALTER TABLE invoice_lines
    ADD COLUMN IF NOT EXISTS cgst_amount NUMERIC(18,4);

ALTER TABLE invoice_lines
    ADD COLUMN IF NOT EXISTS sgst_amount NUMERIC(18,4);

ALTER TABLE invoice_lines
    ADD COLUMN IF NOT EXISTS igst_amount NUMERIC(18,4);

ALTER TABLE raw_material_purchase_items
    ADD COLUMN IF NOT EXISTS cgst_amount NUMERIC(18,4);

ALTER TABLE raw_material_purchase_items
    ADD COLUMN IF NOT EXISTS sgst_amount NUMERIC(18,4);

ALTER TABLE raw_material_purchase_items
    ADD COLUMN IF NOT EXISTS igst_amount NUMERIC(18,4);
