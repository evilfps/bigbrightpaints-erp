-- Flyway v2: optimize normalized invoice-status predicates used by pending-exposure paths.
-- Supports queries that filter active invoices via upper(trim(status)) and join by company/order.

CREATE INDEX IF NOT EXISTS idx_invoices_company_order_status_norm
    ON public.invoices USING btree (company_id, sales_order_id, upper(trim(status)))
    WHERE (sales_order_id IS NOT NULL AND status IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_invoices_company_order_status_null
    ON public.invoices USING btree (company_id, sales_order_id)
    WHERE (sales_order_id IS NOT NULL AND status IS NULL);
