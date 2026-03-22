ALTER TABLE public.opening_stock_imports
    ADD COLUMN IF NOT EXISTS results_json text,
    ADD COLUMN IF NOT EXISTS replay_protection_key varchar(256);

UPDATE public.opening_stock_imports target
SET replay_protection_key = CASE
    WHEN ranked.duplicate_rank = 1 THEN ranked.base_key
    ELSE ranked.base_key || '|LEGACY-DUP|' || target.id::text
END
FROM (
    SELECT source.id,
           'OPENING-STOCK|'
               || COALESCE(
                   NULLIF(regexp_replace(upper(trim(COALESCE(company.code, 'COMPANY'))), '[^A-Z0-9]', '', 'g'), ''),
                   'COMPANY')
               || '|'
               || source.file_hash AS base_key,
           ROW_NUMBER() OVER (
               PARTITION BY source.company_id, lower(trim(source.file_hash))
               ORDER BY source.created_at NULLS FIRST, source.id
           ) AS duplicate_rank
    FROM public.opening_stock_imports source
    LEFT JOIN public.companies company ON company.id = source.company_id
    WHERE source.file_hash IS NOT NULL
      AND length(trim(source.file_hash)) > 0
) ranked
WHERE target.id = ranked.id
  AND COALESCE(trim(target.replay_protection_key), '') = '';

DROP INDEX IF EXISTS idx_opening_stock_imports_company_replay_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_opening_stock_imports_company_replay_key
    ON public.opening_stock_imports (company_id, replay_protection_key)
    WHERE replay_protection_key IS NOT NULL;
