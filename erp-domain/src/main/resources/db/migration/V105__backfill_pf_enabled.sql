-- Backfill PF toggle for existing companies without PF payable account
UPDATE companies c
SET pf_enabled = false
WHERE pf_enabled = true
  AND NOT EXISTS (
      SELECT 1
      FROM accounts a
      WHERE a.company_id = c.id
        AND LOWER(a.code) = 'pf-payable'
  );
