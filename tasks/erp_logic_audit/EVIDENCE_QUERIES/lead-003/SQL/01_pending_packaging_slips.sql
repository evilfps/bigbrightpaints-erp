-- Params:
--   :company_id (numeric)

SELECT id, slip_number, status, sales_order_id
FROM packaging_slips
WHERE company_id = :company_id
ORDER BY id DESC
LIMIT 10;
