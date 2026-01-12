-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Surface period-integrity anomalies:
--   - journal created after close/lock but dated into closed/locked period
--   - documents updated after close/lock for a closed/locked period window
--
-- Notes:
--   This is a heuristic; confirm intent via audit logs and workflow context.

WITH periods AS (
  SELECT
    ap.*,
    COALESCE(ap.closed_at, ap.locked_at) AS cutoff_at
  FROM accounting_periods ap
  WHERE ap.company_id = :company_id
    AND ap.status IN ('CLOSED', 'LOCKED')
    AND COALESCE(ap.closed_at, ap.locked_at) IS NOT NULL
),
journal_backdated AS (
  SELECT
    'journal_created_after_close' AS finding_type,
    je.id AS entity_id,
    je.reference_number,
    je.entry_date,
    je.created_at,
    p.id AS period_id,
    p.year,
    p.month,
    p.status,
    p.cutoff_at
  FROM journal_entries je
  JOIN periods p
    ON p.company_id = je.company_id
   AND je.entry_date BETWEEN p.start_date AND p.end_date
  WHERE je.company_id = :company_id
    AND je.created_at > p.cutoff_at
)
SELECT * FROM journal_backdated
ORDER BY created_at DESC, entity_id DESC;

-- Invoices updated after close/lock for the period containing issue_date
WITH periods AS (
  SELECT
    ap.*,
    COALESCE(ap.closed_at, ap.locked_at) AS cutoff_at
  FROM accounting_periods ap
  WHERE ap.company_id = :company_id
    AND ap.status IN ('CLOSED', 'LOCKED')
    AND COALESCE(ap.closed_at, ap.locked_at) IS NOT NULL
)
SELECT
  'invoice_updated_after_close' AS finding_type,
  i.id AS invoice_id,
  i.invoice_number,
  i.status,
  i.issue_date,
  i.updated_at,
  p.id AS period_id,
  p.year,
  p.month,
  p.status AS period_status,
  p.cutoff_at
FROM invoices i
JOIN periods p
  ON p.company_id = i.company_id
 AND i.issue_date BETWEEN p.start_date AND p.end_date
WHERE i.company_id = :company_id
  AND i.updated_at IS NOT NULL
  AND i.updated_at > p.cutoff_at
ORDER BY i.updated_at DESC, i.id DESC;

-- Purchases updated after close/lock for the period containing invoice_date
WITH periods AS (
  SELECT
    ap.*,
    COALESCE(ap.closed_at, ap.locked_at) AS cutoff_at
  FROM accounting_periods ap
  WHERE ap.company_id = :company_id
    AND ap.status IN ('CLOSED', 'LOCKED')
    AND COALESCE(ap.closed_at, ap.locked_at) IS NOT NULL
)
SELECT
  'purchase_updated_after_close' AS finding_type,
  pch.id AS purchase_id,
  pch.invoice_number,
  pch.status,
  pch.invoice_date,
  pch.updated_at,
  p.id AS period_id,
  p.year,
  p.month,
  p.status AS period_status,
  p.cutoff_at
FROM raw_material_purchases pch
JOIN periods p
  ON p.company_id = pch.company_id
 AND pch.invoice_date BETWEEN p.start_date AND p.end_date
WHERE pch.company_id = :company_id
  AND pch.updated_at IS NOT NULL
  AND pch.updated_at > p.cutoff_at
ORDER BY pch.updated_at DESC, pch.id DESC;

