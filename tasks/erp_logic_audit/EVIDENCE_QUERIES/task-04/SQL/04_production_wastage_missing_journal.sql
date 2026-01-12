-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Identify production logs with wastage that lack a corresponding wastage journal.

SELECT
  pl.id AS production_log_id,
  pl.production_code,
  pl.wastage_quantity,
  je.id AS wastage_journal_id,
  je.reference_number
FROM production_logs pl
LEFT JOIN journal_entries je
  ON je.company_id = pl.company_id
  AND je.reference_number = pl.production_code || '-WASTE'
WHERE pl.company_id = :company_id
  AND pl.wastage_quantity > 0
  AND je.id IS NULL
ORDER BY pl.produced_at DESC, pl.id DESC;
