-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Compare production log material_cost_total to summed raw material movement costs.

WITH movement_costs AS (
  SELECT
    rmm.reference_id AS production_code,
    SUM(rmm.quantity * rmm.unit_cost) AS movement_cost
  FROM raw_material_movements rmm
  JOIN raw_materials rm ON rm.id = rmm.raw_material_id
  WHERE rm.company_id = :company_id
    AND rmm.reference_type = 'PRODUCTION_LOG'
  GROUP BY rmm.reference_id
)
SELECT
  pl.id AS production_log_id,
  pl.production_code,
  pl.material_cost_total,
  COALESCE(mc.movement_cost, 0) AS movement_cost,
  (COALESCE(mc.movement_cost, 0) - COALESCE(pl.material_cost_total, 0)) AS delta
FROM production_logs pl
LEFT JOIN movement_costs mc ON mc.production_code = pl.production_code
WHERE pl.company_id = :company_id
  AND ABS(COALESCE(mc.movement_cost, 0) - COALESCE(pl.material_cost_total, 0)) > 0.01
ORDER BY pl.produced_at DESC, pl.id DESC;
