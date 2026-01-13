-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Identify journals that look like system-generated document journals but are not linked back to the document tables.
-- Caveat:
--   Manual journals may legitimately appear here; treat as a lead unless confirmed to be system-generated.

-- INV-* journals with no invoice linking to the journal entry
SELECT
  'sales_ar_journal_unlinked' AS finding_type,
  je.id AS journal_entry_id,
  je.reference_number,
  je.entry_date,
  je.dealer_id,
  je.status
FROM journal_entries je
WHERE je.company_id = :company_id
  AND je.reference_number LIKE 'INV-%'
  AND NOT EXISTS (
    SELECT 1
    FROM invoices i
    WHERE i.company_id = je.company_id
      AND i.journal_entry_id = je.id
  )
ORDER BY je.entry_date DESC, je.id DESC;

-- COGS-* journals with no packaging slip linking to the journal entry
SELECT
  'cogs_journal_unlinked' AS finding_type,
  je.id AS journal_entry_id,
  je.reference_number,
  je.entry_date,
  je.dealer_id,
  je.status
FROM journal_entries je
WHERE je.company_id = :company_id
  AND je.reference_number LIKE 'COGS-%'
  AND NOT EXISTS (
    SELECT 1
    FROM packaging_slips ps
    WHERE ps.company_id = je.company_id
      AND ps.cogs_journal_entry_id = je.id
  )
ORDER BY je.entry_date DESC, je.id DESC;

