-- Purpose:
--   Inspect idempotency constraints for partner_settlement_allocations.
-- Notes:
--   If (company_id, idempotency_key) is unique, multi-allocation settlements that reuse
--   a single idempotency key will violate the constraint.

SELECT
  indexname,
  indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'partner_settlement_allocations'
ORDER BY indexname;

SELECT
  conname,
  pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'partner_settlement_allocations'::regclass
ORDER BY conname;
