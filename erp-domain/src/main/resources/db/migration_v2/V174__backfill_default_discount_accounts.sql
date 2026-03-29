WITH companies_missing_discount_default AS (
    SELECT c.id
    FROM public.companies c
    WHERE c.default_discount_account_id IS NULL
),
seeded_discount_accounts AS (
    INSERT INTO public.accounts (company_id, code, name, type, balance, active, hierarchy_level)
    SELECT c.id, 'DISC', 'Discounts', 'EXPENSE', 0, TRUE, 1
    FROM companies_missing_discount_default c
    WHERE NOT EXISTS (
        SELECT 1
        FROM public.accounts a
        WHERE a.company_id = c.id
          AND upper(a.code) = 'DISC'
    )
    RETURNING company_id, id
),
resolved_discount_accounts AS (
    SELECT c.id AS company_id,
           COALESCE(
               (SELECT s.id
                FROM seeded_discount_accounts s
                WHERE s.company_id = c.id),
               (SELECT a.id
                FROM public.accounts a
                WHERE a.company_id = c.id
                  AND upper(a.code) = 'DISC'
                ORDER BY a.id
                LIMIT 1),
               (SELECT a.id
                FROM public.accounts a
                WHERE a.company_id = c.id
                  AND upper(a.code) = 'SALES-RETURNS'
                ORDER BY a.id
                LIMIT 1)
           ) AS discount_account_id
    FROM companies_missing_discount_default c
)
UPDATE public.companies c
SET default_discount_account_id = resolved.discount_account_id
FROM resolved_discount_accounts resolved
WHERE c.id = resolved.company_id
  AND c.default_discount_account_id IS NULL
  AND resolved.discount_account_id IS NOT NULL;
