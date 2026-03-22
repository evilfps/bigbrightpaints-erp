ALTER TABLE public.opening_stock_imports
    ADD COLUMN IF NOT EXISTS results_json text,
    ADD COLUMN IF NOT EXISTS replay_protection_key varchar(256);

CREATE INDEX IF NOT EXISTS idx_opening_stock_imports_company_replay_key
    ON public.opening_stock_imports (company_id, replay_protection_key);
