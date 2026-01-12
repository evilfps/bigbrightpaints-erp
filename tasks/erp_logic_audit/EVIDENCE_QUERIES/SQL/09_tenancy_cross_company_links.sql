-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Detect cross-company linking (tenancy boundary breaches) by comparing company_id across linked entities.
-- Notes:
--   Some tables are indirectly company-scoped (via finished_good/raw_material); those checks join through the master.

-- Packaging slip company vs sales order company mismatch
SELECT
  'packaging_slip_sales_order_company_mismatch' AS finding_type,
  ps.id AS packaging_slip_id,
  ps.company_id AS slip_company_id,
  so.company_id AS order_company_id,
  ps.slip_number,
  so.order_number
FROM packaging_slips ps
JOIN sales_orders so ON so.id = ps.sales_order_id
WHERE ps.company_id = :company_id
  AND ps.company_id <> so.company_id;

-- Invoice company vs dealer company mismatch
SELECT
  'invoice_dealer_company_mismatch' AS finding_type,
  i.id AS invoice_id,
  i.company_id AS invoice_company_id,
  d.company_id AS dealer_company_id,
  i.invoice_number,
  d.code AS dealer_code
FROM invoices i
JOIN dealers d ON d.id = i.dealer_id
WHERE i.company_id = :company_id
  AND i.company_id <> d.company_id;

-- Purchase company vs supplier company mismatch
SELECT
  'purchase_supplier_company_mismatch' AS finding_type,
  p.id AS purchase_id,
  p.company_id AS purchase_company_id,
  s.company_id AS supplier_company_id,
  p.invoice_number,
  s.code AS supplier_code
FROM raw_material_purchases p
JOIN suppliers s ON s.id = p.supplier_id
WHERE p.company_id = :company_id
  AND p.company_id <> s.company_id;

-- Journal entry company vs dealer/supplier company mismatch
SELECT
  'journal_dealer_company_mismatch' AS finding_type,
  je.id AS journal_entry_id,
  je.reference_number,
  je.company_id AS journal_company_id,
  je.dealer_id,
  d.company_id AS dealer_company_id
FROM journal_entries je
JOIN dealers d ON d.id = je.dealer_id
WHERE je.company_id = :company_id
  AND je.dealer_id IS NOT NULL
  AND je.company_id <> d.company_id;

SELECT
  'journal_supplier_company_mismatch' AS finding_type,
  je.id AS journal_entry_id,
  je.reference_number,
  je.company_id AS journal_company_id,
  je.supplier_id,
  s.company_id AS supplier_company_id
FROM journal_entries je
JOIN suppliers s ON s.id = je.supplier_id
WHERE je.company_id = :company_id
  AND je.supplier_id IS NOT NULL
  AND je.company_id <> s.company_id;

-- Packaging slip lines: slip company vs finished good batch company mismatch
SELECT
  'packaging_slip_line_batch_company_mismatch' AS finding_type,
  psl.id AS slip_line_id,
  ps.id AS slip_id,
  ps.company_id AS slip_company_id,
  fg.company_id AS batch_company_id,
  fg.product_code,
  ps.slip_number
FROM packaging_slip_lines psl
JOIN packaging_slips ps ON ps.id = psl.packaging_slip_id
JOIN finished_good_batches fgb ON fgb.id = psl.finished_good_batch_id
JOIN finished_goods fg ON fg.id = fgb.finished_good_id
WHERE ps.company_id = :company_id
  AND ps.company_id <> fg.company_id;

