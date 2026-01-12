-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Detect tax/total arithmetic mismatches across documents and their line items.
-- Notes:
--   These are arithmetic checks only; proving “wrong” tax requires policy context.

-- Invoices: subtotal + tax_total must equal total_amount within tolerance
SELECT
  'invoice_header_math_mismatch' AS finding_type,
  i.id AS invoice_id,
  i.invoice_number,
  i.status,
  i.subtotal,
  i.tax_total,
  i.total_amount,
  (COALESCE(i.subtotal, 0) + COALESCE(i.tax_total, 0) - COALESCE(i.total_amount, 0)) AS delta
FROM invoices i
WHERE i.company_id = :company_id
  AND ABS(COALESCE(i.subtotal, 0) + COALESCE(i.tax_total, 0) - COALESCE(i.total_amount, 0)) > 0.01
ORDER BY i.issue_date DESC, i.id DESC;

-- Invoices: sum(lines.line_total) must equal total_amount within tolerance
SELECT
  'invoice_lines_sum_mismatch' AS finding_type,
  i.id AS invoice_id,
  i.invoice_number,
  i.total_amount,
  COALESCE(SUM(il.line_total), 0) AS lines_total,
  (COALESCE(SUM(il.line_total), 0) - COALESCE(i.total_amount, 0)) AS delta
FROM invoices i
LEFT JOIN invoice_lines il ON il.invoice_id = i.id
WHERE i.company_id = :company_id
GROUP BY i.id, i.invoice_number, i.total_amount
HAVING ABS(COALESCE(SUM(il.line_total), 0) - COALESCE(i.total_amount, 0)) > 0.01
ORDER BY i.id DESC;

-- Sales orders: sum(items.line_total) must equal total_amount within tolerance
SELECT
  'sales_order_lines_sum_mismatch' AS finding_type,
  so.id AS sales_order_id,
  so.order_number,
  so.total_amount,
  COALESCE(SUM(soi.line_total), 0) AS lines_total,
  (COALESCE(SUM(soi.line_total), 0) - COALESCE(so.total_amount, 0)) AS delta,
  so.gst_rounding_adjustment
FROM sales_orders so
LEFT JOIN sales_order_items soi ON soi.sales_order_id = so.id
WHERE so.company_id = :company_id
GROUP BY so.id, so.order_number, so.total_amount, so.gst_rounding_adjustment
HAVING ABS(COALESCE(SUM(soi.line_total), 0) - COALESCE(so.total_amount, 0)) > 0.01
ORDER BY so.id DESC;

