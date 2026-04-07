ALTER TABLE public.sales_orders
    ADD COLUMN IF NOT EXISTS payment_terms VARCHAR(128);

ALTER TABLE public.sales_order_items
    ADD COLUMN IF NOT EXISTS finished_good_id BIGINT;

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_sales_order_items_finished_good'
    ) THEN
        ALTER TABLE public.sales_order_items
            ADD CONSTRAINT fk_sales_order_items_finished_good
                FOREIGN KEY (finished_good_id)
                    REFERENCES public.finished_goods (id)
                    ON DELETE SET NULL;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_sales_order_items_finished_good
    ON public.sales_order_items (finished_good_id);
