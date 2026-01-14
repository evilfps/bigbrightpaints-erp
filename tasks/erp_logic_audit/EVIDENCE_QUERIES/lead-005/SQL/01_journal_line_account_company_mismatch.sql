-- Params:
--   :company_id (numeric)

SELECT jl.id AS journal_line_id,
       jl.journal_entry_id,
       je.reference_number,
       je.company_id AS journal_company_id,
       a.id AS account_id,
       a.code AS account_code,
       a.company_id AS account_company_id
FROM journal_lines jl
JOIN journal_entries je ON je.id = jl.journal_entry_id
JOIN accounts a ON a.id = jl.account_id
WHERE je.company_id = :company_id
  AND a.company_id <> je.company_id
ORDER BY jl.id DESC;
