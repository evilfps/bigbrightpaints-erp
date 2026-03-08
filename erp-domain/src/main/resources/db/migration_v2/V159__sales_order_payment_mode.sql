ALTER TABLE public.sales_orders
    ADD COLUMN IF NOT EXISTS payment_mode VARCHAR(32);

UPDATE public.sales_orders
SET payment_mode = 'CREDIT'
WHERE payment_mode IS NULL
   OR btrim(payment_mode) = '';

ALTER TABLE public.sales_orders
    ALTER COLUMN payment_mode SET DEFAULT 'CREDIT';

ALTER TABLE public.sales_orders
    ALTER COLUMN payment_mode SET NOT NULL;
