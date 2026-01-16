-- Purpose:
--   Demonstrate payroll idempotency keys are scoped by company (same key allowed across companies).
-- Notes:
--   Creates two small payroll runs with the same idempotency key in different companies.

WITH company_a AS (
    INSERT INTO companies (name, code)
    VALUES ('LF-007 A Ltd', 'LF-007-A')
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
    RETURNING id
),
company_b AS (
    INSERT INTO companies (name, code)
    VALUES ('LF-007 B Ltd', 'LF-007-B')
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
    RETURNING id
),
companies AS (
    SELECT id FROM company_a
    UNION ALL
    SELECT id FROM company_b
)
INSERT INTO payroll_runs (company_id, run_date, status, total_amount, idempotency_key)
SELECT id, DATE '2026-01-10', 'DRAFT', 1500.00, 'LF-007-IDEMP'
FROM companies
ON CONFLICT DO NOTHING;

SELECT company_id,
       id,
       run_date,
       status,
       total_amount,
       idempotency_key,
       created_at
FROM payroll_runs
WHERE idempotency_key = 'LF-007-IDEMP'
ORDER BY company_id, id;

SELECT company_id,
       COUNT(*) AS run_count
FROM payroll_runs
WHERE idempotency_key = 'LF-007-IDEMP'
GROUP BY company_id
ORDER BY company_id;
