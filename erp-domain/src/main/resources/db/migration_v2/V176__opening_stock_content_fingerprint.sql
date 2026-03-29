ALTER TABLE public.opening_stock_imports
    ADD COLUMN IF NOT EXISTS content_fingerprint varchar(64);

UPDATE public.opening_stock_imports
SET content_fingerprint = encode(
        digest(COALESCE(NULLIF(opening_stock_batch_key, ''), idempotency_key, ''), 'sha256'),
        'hex')
WHERE COALESCE(content_fingerprint, '') = '';

ALTER TABLE public.opening_stock_imports
    ALTER COLUMN content_fingerprint SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_opening_stock_imports_company_content_fingerprint
    ON public.opening_stock_imports (company_id, content_fingerprint);
