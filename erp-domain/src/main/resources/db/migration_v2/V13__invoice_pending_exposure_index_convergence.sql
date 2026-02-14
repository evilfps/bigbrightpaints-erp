-- Flyway v2 forward-fix for V12 index convergence.
-- Preserve V12 checksum stability while removing the impossible null-status companion index.

DROP INDEX IF EXISTS public.idx_invoices_company_order_status_null;
