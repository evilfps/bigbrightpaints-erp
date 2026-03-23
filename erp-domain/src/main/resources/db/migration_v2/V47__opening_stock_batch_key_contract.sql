ALTER TABLE public.opening_stock_imports
    ADD COLUMN IF NOT EXISTS opening_stock_batch_key varchar(128);

UPDATE public.opening_stock_imports
SET opening_stock_batch_key = COALESCE(
        opening_stock_batch_key,
        NULLIF(
            split_part(replay_protection_key, '|', 3)
                || CASE
                    WHEN replay_protection_key LIKE '%|LEGACY-DUP|%'
                        THEN '|LEGACY-DUP|' || split_part(replay_protection_key, '|', 5)
                    ELSE ''
                END,
            ''
        )
    )
WHERE replay_protection_key IS NOT NULL
  AND opening_stock_batch_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_opening_stock_imports_company_batch_key
    ON public.opening_stock_imports (company_id, opening_stock_batch_key)
    WHERE opening_stock_batch_key IS NOT NULL;
