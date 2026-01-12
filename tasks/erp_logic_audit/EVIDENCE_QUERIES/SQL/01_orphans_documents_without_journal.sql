-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Detect posted/active documents that are missing required journal links.

-- Invoices: status != DRAFT but journal link missing
SELECT
  'invoice_missing_je' AS finding_type,
  i.id AS document_id,
  i.invoice_number AS document_number,
  i.status,
  i.issue_date,
  i.journal_entry_id
FROM invoices i
WHERE i.company_id = :company_id
  AND i.status <> 'DRAFT'
  AND i.journal_entry_id IS NULL
ORDER BY i.issue_date DESC, i.id DESC;

-- Purchases: status POSTED but journal link missing
SELECT
  'purchase_missing_je' AS finding_type,
  p.id AS document_id,
  p.invoice_number AS document_number,
  p.status,
  p.invoice_date,
  p.journal_entry_id
FROM raw_material_purchases p
WHERE p.company_id = :company_id
  AND p.status = 'POSTED'
  AND p.journal_entry_id IS NULL
ORDER BY p.invoice_date DESC, p.id DESC;

-- Payroll: POSTED/PAID but both journal link fields are missing
SELECT
  'payroll_missing_je' AS finding_type,
  pr.id AS document_id,
  pr.run_number AS document_number,
  pr.status,
  pr.period_start,
  pr.period_end,
  pr.journal_entry_id,
  pr.journal_entry_ref_id
FROM payroll_runs pr
WHERE pr.company_id = :company_id
  AND pr.status IN ('POSTED', 'PAID')
  AND pr.journal_entry_id IS NULL
  AND pr.journal_entry_ref_id IS NULL
ORDER BY pr.period_end DESC, pr.id DESC;

-- Dispatch: DISPATCHED slip missing core link fields
SELECT
  'dispatch_slip_missing_links' AS finding_type,
  ps.id AS slip_id,
  ps.slip_number,
  ps.status,
  ps.dispatched_at,
  ps.invoice_id,
  ps.journal_entry_id,
  ps.cogs_journal_entry_id
FROM packaging_slips ps
WHERE ps.company_id = :company_id
  AND ps.status = 'DISPATCHED'
  AND (
    ps.invoice_id IS NULL
    OR ps.journal_entry_id IS NULL
    OR ps.cogs_journal_entry_id IS NULL
  )
ORDER BY ps.dispatched_at DESC NULLS LAST, ps.id DESC;

