-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Compare WIP debits from material consumption vs WIP credits from semi-finished receipt
--   when labor/overhead are non-zero. Highlights potential WIP over-crediting.

WITH log_costs AS (
  SELECT
    pl.id AS production_log_id,
    pl.production_code,
    pl.material_cost_total,
    pl.labor_cost_total,
    pl.overhead_cost_total,
    (pl.material_cost_total + pl.labor_cost_total + pl.overhead_cost_total) AS total_cost,
    NULLIF(pp.metadata->>'wipAccountId', '')::bigint AS wip_account_id
  FROM production_logs pl
  JOIN production_products pp ON pp.id = pl.product_id
  WHERE pl.company_id = :company_id
)
SELECT
  lc.production_log_id,
  lc.production_code,
  lc.material_cost_total,
  lc.labor_cost_total,
  lc.overhead_cost_total,
  lc.total_cost,
  lc.wip_account_id,
  COALESCE(rm.wip_debit, 0) AS wip_debit_from_rm,
  COALESCE(sf.wip_credit, 0) AS wip_credit_from_semifg,
  (COALESCE(rm.wip_debit, 0) - COALESCE(sf.wip_credit, 0)) AS wip_delta
FROM log_costs lc
LEFT JOIN LATERAL (
  SELECT SUM(jl.debit) AS wip_debit
  FROM journal_entries je
  JOIN journal_lines jl ON jl.journal_entry_id = je.id
  WHERE je.company_id = :company_id
    AND je.reference_number = lc.production_code || '-RM'
    AND jl.account_id = lc.wip_account_id
) rm ON true
LEFT JOIN LATERAL (
  SELECT SUM(jl.credit) AS wip_credit
  FROM journal_entries je
  JOIN journal_lines jl ON jl.journal_entry_id = je.id
  WHERE je.company_id = :company_id
    AND je.reference_number = lc.production_code || '-SEMIFG'
    AND jl.account_id = lc.wip_account_id
) sf ON true
WHERE lc.wip_account_id IS NOT NULL
  AND (COALESCE(lc.labor_cost_total, 0) + COALESCE(lc.overhead_cost_total, 0)) > 0
  AND ABS(COALESCE(rm.wip_debit, 0) - COALESCE(sf.wip_credit, 0)) > 0.01
ORDER BY lc.production_log_id DESC;
