-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Compare computed FIFO valuation to the inventory control balance selection logic used in `ReportService`.

WITH company AS (
  SELECT *
  FROM companies
  WHERE id = :company_id
),
inventory_control AS (
  SELECT
    CASE
      WHEN c.default_inventory_account_id IS NOT NULL THEN (
        SELECT COALESCE(a.balance, 0)
        FROM accounts a
        WHERE a.company_id = c.id AND a.id = c.default_inventory_account_id
      )
      ELSE (
        SELECT COALESCE(SUM(COALESCE(a.balance, 0)), 0)
        FROM accounts a
        WHERE a.company_id = c.id
          AND a.name IS NOT NULL
          AND LOWER(a.name) LIKE '%inventory%'
      )
    END AS ledger_balance
  FROM company c
),
valuation AS (
  -- Inline the totals from 06_inventory_valuation_fifo.sql
  WITH rm AS (
    SELECT id, COALESCE(current_stock, 0) AS required_qty
    FROM raw_materials
    WHERE company_id = :company_id
  ),
  rm_batches AS (
    SELECT
      b.raw_material_id,
      COALESCE(b.quantity, 0) AS qty,
      COALESCE(b.cost_per_unit, 0) AS cost,
      b.received_at,
      SUM(COALESCE(b.quantity, 0)) OVER (
        PARTITION BY b.raw_material_id
        ORDER BY b.received_at
        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
      ) AS cum_before,
      SUM(COALESCE(b.quantity, 0)) OVER (
        PARTITION BY b.raw_material_id
        ORDER BY b.received_at
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
      ) AS total_batch_qty
    FROM raw_material_batches b
  ),
  rm_last_cost AS (
    SELECT DISTINCT ON (b.raw_material_id)
      b.raw_material_id,
      COALESCE(b.cost_per_unit, 0) AS last_cost
    FROM raw_material_batches b
    ORDER BY b.raw_material_id, b.received_at DESC NULLS LAST, b.id DESC
  ),
  rm_valued AS (
    SELECT
      r.id AS raw_material_id,
      (
        COALESCE(SUM(
          GREATEST(0, LEAST(rb.qty, r.required_qty - COALESCE(rb.cum_before, 0))) * rb.cost
        ), 0)
        + (GREATEST(0, r.required_qty - COALESCE(MAX(rb.total_batch_qty), 0)) * COALESCE(lc.last_cost, 0))
      ) AS total_value
    FROM rm r
    LEFT JOIN rm_batches rb ON rb.raw_material_id = r.id
    LEFT JOIN rm_last_cost lc ON lc.raw_material_id = r.id
    GROUP BY r.id, r.required_qty, lc.last_cost
  ),
  fg AS (
    SELECT id, COALESCE(current_stock, 0) AS required_qty
    FROM finished_goods
    WHERE company_id = :company_id
  ),
  fg_batches AS (
    SELECT
      b.finished_good_id,
      COALESCE(b.quantity_available, 0) AS qty,
      COALESCE(b.unit_cost, 0) AS cost,
      b.manufactured_at,
      SUM(COALESCE(b.quantity_available, 0)) OVER (
        PARTITION BY b.finished_good_id
        ORDER BY b.manufactured_at
        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
      ) AS cum_before,
      SUM(COALESCE(b.quantity_available, 0)) OVER (
        PARTITION BY b.finished_good_id
        ORDER BY b.manufactured_at
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
      ) AS total_batch_qty
    FROM finished_good_batches b
  ),
  fg_last_cost AS (
    SELECT DISTINCT ON (b.finished_good_id)
      b.finished_good_id,
      COALESCE(b.unit_cost, 0) AS last_cost
    FROM finished_good_batches b
    ORDER BY b.finished_good_id, b.manufactured_at DESC NULLS LAST, b.id DESC
  ),
  fg_valued AS (
    SELECT
      f.id AS finished_good_id,
      (
        COALESCE(SUM(
          GREATEST(0, LEAST(fb.qty, f.required_qty - COALESCE(fb.cum_before, 0))) * fb.cost
        ), 0)
        + (GREATEST(0, f.required_qty - COALESCE(MAX(fb.total_batch_qty), 0)) * COALESCE(lc.last_cost, 0))
      ) AS total_value
    FROM fg f
    LEFT JOIN fg_batches fb ON fb.finished_good_id = f.id
    LEFT JOIN fg_last_cost lc ON lc.finished_good_id = f.id
    GROUP BY f.id, f.required_qty, lc.last_cost
  )
  SELECT
    COALESCE((SELECT SUM(total_value) FROM rm_valued), 0)
    + COALESCE((SELECT SUM(total_value) FROM fg_valued), 0) AS inventory_value
)
SELECT
  v.inventory_value,
  ic.ledger_balance,
  (v.inventory_value - ic.ledger_balance) AS variance
FROM valuation v
CROSS JOIN inventory_control ic;

