-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Find packaging material movements with non-zero cost that are missing journal links.

SELECT
  rmm.id AS movement_id,
  rmm.reference_id,
  rmm.quantity,
  rmm.unit_cost,
  (rmm.quantity * rmm.unit_cost) AS movement_value,
  rmm.journal_entry_id,
  je.reference_number AS journal_reference
FROM raw_material_movements rmm
JOIN raw_materials rm ON rm.id = rmm.raw_material_id
LEFT JOIN journal_entries je ON je.id = rmm.journal_entry_id
WHERE rm.company_id = :company_id
  AND rmm.reference_type = 'PACKING_RECORD'
  AND (rmm.quantity * rmm.unit_cost) > 0.01
  AND (rmm.journal_entry_id IS NULL
       OR je.reference_number IS DISTINCT FROM (rmm.reference_id || '-PACKMAT'))
ORDER BY rmm.id DESC;
