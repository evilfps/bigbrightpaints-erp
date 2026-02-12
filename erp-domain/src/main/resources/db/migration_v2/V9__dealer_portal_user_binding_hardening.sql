-- Flyway v2: harden dealer portal user binding to prevent ambiguous principal mappings.
-- 1) Backfill dealer.portal_user_id from normalized dealer email when unbound.
-- 2) Collapse same-company duplicate portal mappings to fail-closed null for non-canonical rows.
-- 3) Enforce unique (company_id, portal_user_id) for non-null bindings.

UPDATE public.dealers d
SET portal_user_id = u.id
FROM public.app_users u
WHERE d.portal_user_id IS NULL
  AND d.email IS NOT NULL
  AND lower(btrim(d.email)) = lower(btrim(u.email));

WITH ranked AS (
    SELECT
        id,
        row_number() OVER (PARTITION BY company_id, portal_user_id ORDER BY id) AS rn
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
