-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Compute an inventory valuation approximation that mirrors the as-built FIFO slice logic in `ReportService`.
-- Reference:
--   `ReportService#valueFromRawMaterial`, `#valueFromFinishedGood`, `#consumeValuation`
--   in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java`

WITH rm AS (
  SELECT id, sku, name, COALESCE(current_stock, 0) AS required_qty
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
rm_fifo AS (
  SELECT
    r.id AS raw_material_id,
    r.sku,
    r.name,
    r.required_qty,
    COALESCE(SUM(
      GREATEST(
        0,
        LEAST(
          rb.qty,
          r.required_qty - COALESCE(rb.cum_before, 0)
        )
      ) * rb.cost
    ), 0) AS fifo_value,
    COALESCE(MAX(rb.total_batch_qty), 0) AS total_batch_qty,
    COALESCE(lc.last_cost, 0) AS last_cost
  FROM rm r
  LEFT JOIN rm_batches rb ON rb.raw_material_id = r.id
  LEFT JOIN rm_last_cost lc ON lc.raw_material_id = r.id
  GROUP BY r.id, r.sku, r.name, r.required_qty, lc.last_cost
),
rm_valued AS (
  SELECT
    raw_material_id,
    sku,
    name,
    required_qty,
    fifo_value,
    GREATEST(0, required_qty - total_batch_qty) AS extra_qty,
    last_cost,
    (fifo_value + (GREATEST(0, required_qty - total_batch_qty) * last_cost)) AS total_value
  FROM rm_fifo
),
fg AS (
  SELECT id, product_code, name, COALESCE(current_stock, 0) AS required_qty
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
fg_fifo AS (
  SELECT
    f.id AS finished_good_id,
    f.product_code,
    f.name,
    f.required_qty,
    COALESCE(SUM(
      GREATEST(
        0,
        LEAST(
          fb.qty,
          f.required_qty - COALESCE(fb.cum_before, 0)
        )
      ) * fb.cost
    ), 0) AS fifo_value,
    COALESCE(MAX(fb.total_batch_qty), 0) AS total_batch_qty,
    COALESCE(lc.last_cost, 0) AS last_cost
  FROM fg f
  LEFT JOIN fg_batches fb ON fb.finished_good_id = f.id
  LEFT JOIN fg_last_cost lc ON lc.finished_good_id = f.id
  GROUP BY f.id, f.product_code, f.name, f.required_qty, lc.last_cost
),
fg_valued AS (
  SELECT
    finished_good_id,
    product_code,
    name,
    required_qty,
    fifo_value,
    GREATEST(0, required_qty - total_batch_qty) AS extra_qty,
    last_cost,
    (fifo_value + (GREATEST(0, required_qty - total_batch_qty) * last_cost)) AS total_value
  FROM fg_fifo
)
SELECT
  'raw_materials' AS category,
  SUM(total_value) AS total_value
FROM rm_valued
UNION ALL
SELECT
  'finished_goods' AS category,
  SUM(total_value) AS total_value
FROM fg_valued;

