-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Detect reservations that can block dispatch but have no corresponding packaging slip.
-- Reference:
--   Similar to `ReconciliationService#findOrphanReservations`.

SELECT
  ir.id AS reservation_id,
  ir.reference_type,
  ir.reference_id,
  ir.status,
  ir.quantity,
  ir.reserved_quantity,
  ir.fulfilled_quantity,
  ir.created_at
FROM inventory_reservations ir
JOIN finished_goods fg ON fg.id = ir.finished_good_id
WHERE fg.company_id = :company_id
  AND ir.status = 'RESERVED'
  AND ir.reference_type = 'SALES_ORDER'
  AND NOT EXISTS (
    SELECT 1
    FROM packaging_slips ps
    WHERE ps.company_id = fg.company_id
      AND ps.sales_order_id::text = ir.reference_id
  )
ORDER BY ir.created_at DESC, ir.id DESC;

