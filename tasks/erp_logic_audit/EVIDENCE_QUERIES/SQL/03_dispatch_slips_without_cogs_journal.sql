-- Params:
--   :company_id (numeric)
--
-- Purpose:
--   Detect DISPATCHED packaging slips without a resolvable COGS journal entry.
-- Reference mapping (as-built):
--   COGS journal reference is: 'COGS-' + UPPER(regexp_replace(<slip_number>, '[^A-Za-z0-9-]', '', 'g'))

WITH slips AS (
  SELECT
    ps.*,
    ('COGS-' || UPPER(regexp_replace(ps.slip_number, '[^A-Za-z0-9-]', '', 'g'))) AS expected_cogs_reference
  FROM packaging_slips ps
  WHERE ps.company_id = :company_id
    AND ps.status = 'DISPATCHED'
)
SELECT
  s.id AS slip_id,
  s.slip_number,
  s.cogs_journal_entry_id,
  s.expected_cogs_reference,
  je.id AS matched_journal_id,
  je.reference_number AS matched_reference
FROM slips s
LEFT JOIN journal_entries je
  ON je.company_id = s.company_id
  AND je.reference_number = s.expected_cogs_reference
WHERE s.cogs_journal_entry_id IS NULL
  AND je.id IS NULL
ORDER BY s.dispatched_at DESC NULLS LAST, s.id DESC;

