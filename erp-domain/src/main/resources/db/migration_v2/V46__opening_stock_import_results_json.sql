ALTER TABLE public.opening_stock_imports
    ADD COLUMN IF NOT EXISTS results_json text;
