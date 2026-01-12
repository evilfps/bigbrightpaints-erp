-- Purpose:
--   Confirm orchestrator audit trace storage is (or is not) company-scoped.
-- Notes:
--   If no company_id column exists, trace retrieval cannot be scoped by company.

SELECT
  column_name,
  data_type,
  is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'orchestrator_audit'
ORDER BY ordinal_position;

SELECT
  indexname,
  indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'orchestrator_audit'
ORDER BY indexname;
