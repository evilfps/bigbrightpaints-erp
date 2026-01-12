-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Detect duplicate business keys / idempotency keys that should be unique per company.

-- Sales orders: duplicate idempotency keys
SELECT
  'sales_order_duplicate_idempotency_key' AS finding_type,
  so.idempotency_key,
  COUNT(*) AS order_count,
  MIN(so.id) AS min_order_id,
  MAX(so.id) AS max_order_id
FROM sales_orders so
WHERE so.company_id = :company_id
  AND so.idempotency_key IS NOT NULL
  AND so.idempotency_key <> ''
GROUP BY so.idempotency_key
HAVING COUNT(*) > 1
ORDER BY order_count DESC, max_order_id DESC;

-- Partner settlement allocations: duplicate idempotency keys
SELECT
  'settlement_duplicate_idempotency_key' AS finding_type,
  psa.idempotency_key,
  COUNT(*) AS allocation_rows,
  MIN(psa.id) AS min_id,
  MAX(psa.id) AS max_id
FROM partner_settlement_allocations psa
WHERE psa.company_id = :company_id
  AND psa.idempotency_key IS NOT NULL
  AND psa.idempotency_key <> ''
GROUP BY psa.idempotency_key
HAVING COUNT(*) > 1
ORDER BY allocation_rows DESC, max_id DESC;

-- Journal reference mappings: duplicates should not exist (unique per company + legacy_reference)
SELECT
  'journal_reference_mapping_duplicate' AS finding_type,
  jrm.legacy_reference,
  COUNT(*) AS row_count
FROM journal_reference_mappings jrm
WHERE jrm.company_id = :company_id
GROUP BY jrm.legacy_reference
HAVING COUNT(*) > 1
ORDER BY row_count DESC;

