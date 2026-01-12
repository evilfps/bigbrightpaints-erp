-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Find movements that appear to be financially-impacting but are missing journal links.
-- Notes:
--   Some movement types may be intentionally unlinked (e.g., opening stock); interpret results with workflow context.

-- Finished goods movements by reference type
SELECT
  'fg_movement_missing_je' AS finding_type,
  im.id AS movement_id,
  fg.product_code,
  im.reference_type,
  im.reference_id,
  im.movement_type,
  im.quantity,
  im.unit_cost,
  im.journal_entry_id,
  im.created_at
FROM inventory_movements im
JOIN finished_goods fg ON fg.id = im.finished_good_id
WHERE fg.company_id = :company_id
  AND im.reference_type NOT IN ('OPENING_STOCK')
  AND im.journal_entry_id IS NULL
ORDER BY im.created_at DESC, im.id DESC;

-- Raw material movements by reference type
SELECT
  'rm_movement_missing_je' AS finding_type,
  rmm.id AS movement_id,
  rm.sku,
  rmm.reference_type,
  rmm.reference_id,
  rmm.movement_type,
  rmm.quantity,
  rmm.unit_cost,
  rmm.journal_entry_id,
  rmm.created_at
FROM raw_material_movements rmm
JOIN raw_materials rm ON rm.id = rmm.raw_material_id
WHERE rm.company_id = :company_id
  AND rmm.reference_type NOT IN ('OPENING_STOCK')
  AND rmm.journal_entry_id IS NULL
ORDER BY rmm.created_at DESC, rmm.id DESC;

