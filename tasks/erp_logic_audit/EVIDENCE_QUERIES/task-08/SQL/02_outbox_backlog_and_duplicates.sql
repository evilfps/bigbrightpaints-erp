-- Params:
--   :company_code (string, optional; used only if your outbox encodes company in aggregate_id/payload)
--
-- Purpose:
--   Detect outbox backlog and potential duplicate event emission.
-- Notes:
--   The outbox schema does not include a company_id column; filter strategy depends on how aggregate_id is used.

-- Backlog summary by status
SELECT
  status,
  dead_letter,
  COUNT(*) AS event_count,
  MIN(created_at) AS oldest_created_at,
  MAX(created_at) AS newest_created_at
FROM orchestrator_outbox
GROUP BY status, dead_letter
ORDER BY status, dead_letter;

-- Pending events that are overdue for attempt
SELECT
  id,
  aggregate_type,
  aggregate_id,
  event_type,
  status,
  retry_count,
  next_attempt_at,
  created_at,
  dead_letter,
  LEFT(COALESCE(last_error, ''), 200) AS last_error_snip
FROM orchestrator_outbox
WHERE dead_letter = FALSE
  AND status IN ('PENDING', 'FAILED')
  AND next_attempt_at < NOW()
ORDER BY next_attempt_at ASC, created_at ASC
LIMIT 200;

-- Potential duplicates: same (aggregate_type, aggregate_id, event_type) repeated
SELECT
  aggregate_type,
  aggregate_id,
  event_type,
  COUNT(*) AS row_count,
  MIN(created_at) AS first_seen,
  MAX(created_at) AS last_seen
FROM orchestrator_outbox
GROUP BY aggregate_type, aggregate_id, event_type
HAVING COUNT(*) > 1
ORDER BY row_count DESC, last_seen DESC
LIMIT 200;

