-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Validate that production-related FG inventory receipts link to the expected journal reference.

WITH movements AS (
  SELECT
    im.id AS movement_id,
    im.reference_id AS production_code,
    im.movement_type,
    im.quantity,
    im.unit_cost,
    im.journal_entry_id,
    fgb.batch_code,
    CASE
      WHEN fgb.batch_code = pl.production_code THEN pl.production_code || '-SEMIFG'
      ELSE pl.production_code || '-PACK-' || im.id
    END AS expected_reference,
    je.reference_number AS actual_reference
  FROM inventory_movements im
  JOIN finished_goods fg ON fg.id = im.finished_good_id
  LEFT JOIN finished_good_batches fgb ON fgb.id = im.finished_good_batch_id
  JOIN production_logs pl ON pl.company_id = fg.company_id
    AND pl.production_code = im.reference_id
  LEFT JOIN journal_entries je ON je.id = im.journal_entry_id
  WHERE fg.company_id = :company_id
    AND im.reference_type = 'PRODUCTION_LOG'
    AND im.movement_type = 'RECEIPT'
)
SELECT *
FROM movements
WHERE journal_entry_id IS NULL
   OR actual_reference IS DISTINCT FROM expected_reference
ORDER BY movement_id DESC;
