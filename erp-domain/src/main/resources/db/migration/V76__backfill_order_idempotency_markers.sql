-- Backfill idempotency markers for existing shipped/invoiced orders
-- This prevents double-posting on retry for legacy data

-- 1. Backfill fulfillment_invoice_id from invoices table
UPDATE sales_orders so
SET fulfillment_invoice_id = inv.id
FROM invoices inv
WHERE inv.sales_order_id = so.id
  AND so.fulfillment_invoice_id IS NULL;

-- 2. Backfill sales_journal_entry_id from journal entries with SALE-{id} reference
UPDATE sales_orders so
SET sales_journal_entry_id = je.id
FROM journal_entries je
WHERE je.reference_number = 'SALE-' || so.id::text
  AND je.company_id = so.company_id
  AND so.sales_journal_entry_id IS NULL;

-- 3. Backfill cogs_journal_entry_id from first COGS journal for each order
UPDATE sales_orders so
SET cogs_journal_entry_id = (
    SELECT MIN(je.id)
    FROM journal_entries je
    WHERE je.reference_number LIKE so.order_number || '-COGS-%'
      AND je.company_id = so.company_id
)
WHERE so.cogs_journal_entry_id IS NULL
  AND EXISTS (
    SELECT 1 FROM journal_entries je
    WHERE je.reference_number LIKE so.order_number || '-COGS-%'
      AND je.company_id = so.company_id
  );

-- 4. Also try COGS- prefix pattern (older format)
UPDATE sales_orders so
SET cogs_journal_entry_id = (
    SELECT MIN(je.id)
    FROM journal_entries je
    WHERE je.reference_number LIKE 'COGS-' || so.order_number || '%'
      AND je.company_id = so.company_id
)
WHERE so.cogs_journal_entry_id IS NULL
  AND EXISTS (
    SELECT 1 FROM journal_entries je
    WHERE je.reference_number LIKE 'COGS-' || so.order_number || '%'
      AND je.company_id = so.company_id
  );

-- Log backfill results
DO $$
DECLARE
    inv_count INTEGER;
    sales_je_count INTEGER;
    cogs_je_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO inv_count FROM sales_orders WHERE fulfillment_invoice_id IS NOT NULL;
    SELECT COUNT(*) INTO sales_je_count FROM sales_orders WHERE sales_journal_entry_id IS NOT NULL;
    SELECT COUNT(*) INTO cogs_je_count FROM sales_orders WHERE cogs_journal_entry_id IS NOT NULL;

    RAISE NOTICE 'Backfill complete: % orders with invoice, % with sales journal, % with COGS journal',
        inv_count, sales_je_count, cogs_je_count;
END $$;
