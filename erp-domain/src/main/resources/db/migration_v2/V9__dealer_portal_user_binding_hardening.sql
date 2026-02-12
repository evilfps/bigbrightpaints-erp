-- Flyway v2: harden dealer portal user binding to prevent ambiguous principal mappings.
-- 1) Backfill dealer.portal_user_id from normalized dealer email when unbound,
--    scoped to explicit company membership (`user_companies`) and only when
--    normalized email mapping is unambiguous within that company.
-- 2) Collapse same-company duplicate portal mappings to fail-closed null for non-canonical rows.
-- 3) Enforce unique (company_id, portal_user_id) for non-null bindings.

WITH canonical_users AS (
    SELECT
        uc.company_id,
        lower(btrim(u.email)) AS normalized_email,
        min(u.id) AS user_id
    FROM public.app_users u
    JOIN public.user_companies uc ON uc.user_id = u.id
    WHERE u.email IS NOT NULL
    GROUP BY uc.company_id, lower(btrim(u.email))
    HAVING count(*) = 1
)
UPDATE public.dealers d
SET portal_user_id = cu.user_id
FROM canonical_users cu
WHERE d.portal_user_id IS NULL
  AND d.email IS NOT NULL
  AND cu.company_id = d.company_id
  AND lower(btrim(d.email)) = cu.normalized_email;

WITH ranked AS (
    SELECT
        id,
        row_number() OVER (
            PARTITION BY company_id, portal_user_id
            ORDER BY
                CASE WHEN upper(status) = 'ACTIVE' THEN 0 ELSE 1 END,
                created_at DESC NULLS LAST,
                id DESC
        ) AS rn
    FROM public.dealers
    WHERE portal_user_id IS NOT NULL
)
UPDATE public.dealers d
SET portal_user_id = NULL
FROM ranked r
WHERE d.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dealers_company_portal_user
    ON public.dealers USING btree (company_id, portal_user_id)
    WHERE (portal_user_id IS NOT NULL);
