-- Params:
--   :company_id (numeric)

SELECT id, code, name, type
FROM accounts
WHERE company_id = :company_id
  AND (code ILIKE '%AR%' OR code ILIKE '%RECEIVABLE%' OR code ILIKE '%AP%' OR code ILIKE '%PAYABLE%')
ORDER BY code;
