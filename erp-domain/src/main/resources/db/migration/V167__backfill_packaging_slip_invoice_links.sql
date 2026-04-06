-- Backfill explicit packaging_slips.invoice_id links for legacy dispatch records.
-- Once populated, invoice/read-model code can rely on the persisted dispatch-owned link.

WITH active_slips AS (
    SELECT p.company_id,
           p.sales_order_id,
           MIN(p.id) AS slip_id,
           COUNT(*) AS active_count
    FROM packaging_slips p
    WHERE p.sales_order_id IS NOT NULL
      AND COALESCE(UPPER(p.status), 'PENDING') <> 'CANCELLED'
    GROUP BY p.company_id, p.sales_order_id
),
current_invoices AS (
    SELECT i.company_id,
           i.sales_order_id,
           MIN(i.id) AS invoice_id,
           COUNT(*) AS current_count
    FROM invoices i
    WHERE i.sales_order_id IS NOT NULL
      AND COALESCE(UPPER(i.status), 'ISSUED') NOT IN ('DRAFT', 'VOID', 'REVERSED')
    GROUP BY i.company_id, i.sales_order_id
)
UPDATE packaging_slips p
SET invoice_id = COALESCE(fulfillment_invoice.id, current_invoices.invoice_id)
FROM active_slips
JOIN sales_orders so
  ON so.company_id = active_slips.company_id
 AND so.id = active_slips.sales_order_id
LEFT JOIN invoices fulfillment_invoice
  ON fulfillment_invoice.id = so.fulfillment_invoice_id
 AND fulfillment_invoice.company_id = so.company_id
 AND fulfillment_invoice.sales_order_id = so.id
 AND COALESCE(UPPER(fulfillment_invoice.status), 'ISSUED') NOT IN ('DRAFT', 'VOID', 'REVERSED')
LEFT JOIN current_invoices
  ON current_invoices.company_id = so.company_id
 AND current_invoices.sales_order_id = so.id
WHERE p.id = active_slips.slip_id
  AND p.invoice_id IS NULL
  AND active_slips.active_count = 1
  AND (fulfillment_invoice.id IS NOT NULL OR current_invoices.current_count = 1);

DO $$
DECLARE
    linked_count integer;
BEGIN
    SELECT COUNT(*) INTO linked_count
    FROM packaging_slips
    WHERE invoice_id IS NOT NULL;

    RAISE NOTICE 'Explicit packaging slip invoice links available for % rows', linked_count;
END $$;
