ALTER TABLE public.opening_stock_imports
    ADD COLUMN IF NOT EXISTS opening_stock_batch_key varchar(128),
    ADD COLUMN IF NOT EXISTS results_json text;

DROP INDEX IF EXISTS idx_opening_stock_import_company_batch_key;
DROP INDEX IF EXISTS idx_opening_stock_imports_company_batch_key;
DROP INDEX IF EXISTS idx_opening_stock_imports_replay_protection;
DROP INDEX IF EXISTS uq_opening_stock_imports_company_batch_key;
DROP INDEX IF EXISTS uq_opening_stock_imports_company_replay_key;

UPDATE public.opening_stock_imports
SET opening_stock_batch_key = idempotency_key
WHERE COALESCE(trim(opening_stock_batch_key), '') = ''
   OR replay_protection_key IS NOT NULL;

WITH RECURSIVE ranked_batch_keys AS (
    SELECT id,
           company_id,
           opening_stock_batch_key,
           ROW_NUMBER() OVER (
               PARTITION BY company_id, opening_stock_batch_key
               ORDER BY CASE WHEN replay_protection_key IS NULL THEN 0 ELSE 1 END,
                        created_at ASC NULLS LAST,
                        id ASC
           ) AS batch_rank
    FROM public.opening_stock_imports
    WHERE opening_stock_batch_key IS NOT NULL
      AND opening_stock_batch_key <> ''
), dedupe_candidates AS (
    SELECT ranked.id,
           ranked.company_id,
           ranked.opening_stock_batch_key,
           0 AS suffix,
           LEFT(
               ranked.opening_stock_batch_key,
               GREATEST(1, 128 - LENGTH('-LEGACY-') - LENGTH(ranked.id::text))
           ) || '-LEGACY-' || ranked.id::text AS deduplicated_batch_key
    FROM ranked_batch_keys ranked
    WHERE ranked.batch_rank > 1

    UNION ALL

    SELECT candidate.id,
           candidate.company_id,
           candidate.opening_stock_batch_key,
           candidate.suffix + 1,
           LEFT(
               candidate.opening_stock_batch_key,
               GREATEST(1, 128 - LENGTH('-LEGACY-') - LENGTH(candidate.id::text) - LENGTH('-') - LENGTH((candidate.suffix + 1)::text))
           ) || '-LEGACY-' || candidate.id::text || '-' || (candidate.suffix + 1)::text AS deduplicated_batch_key
    FROM dedupe_candidates candidate
    WHERE EXISTS (
        SELECT 1
        FROM public.opening_stock_imports imports
        WHERE imports.company_id = candidate.company_id
          AND imports.id <> candidate.id
          AND imports.opening_stock_batch_key = candidate.deduplicated_batch_key
    )
), deduplicated_batch_keys AS (
    SELECT DISTINCT ON (candidate.id)
           candidate.id,
           candidate.deduplicated_batch_key
    FROM dedupe_candidates candidate
    WHERE NOT EXISTS (
        SELECT 1
        FROM public.opening_stock_imports imports
        WHERE imports.company_id = candidate.company_id
          AND imports.id <> candidate.id
          AND imports.opening_stock_batch_key = candidate.deduplicated_batch_key
    )
    ORDER BY candidate.id, candidate.suffix
)
UPDATE public.opening_stock_imports imports
SET opening_stock_batch_key = deduplicated_batch_keys.deduplicated_batch_key
FROM deduplicated_batch_keys
WHERE imports.id = deduplicated_batch_keys.id;

ALTER TABLE public.opening_stock_imports
    ALTER COLUMN opening_stock_batch_key SET NOT NULL;

ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS replay_protection_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_opening_stock_import_company_batch_key
    ON public.opening_stock_imports (company_id, opening_stock_batch_key);
