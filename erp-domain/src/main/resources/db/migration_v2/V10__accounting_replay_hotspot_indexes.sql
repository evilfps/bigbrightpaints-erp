-- Flyway v2: replay/idempotency hotspot index hardening for accounting settlement lookups.
-- These indexes target high-frequency read paths in AccountingService replay and audit helpers.

CREATE INDEX IF NOT EXISTS idx_partner_settlement_company_journal_created_id
    ON public.partner_settlement_allocations USING btree (company_id, journal_entry_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_partner_settlement_idem_ci_created_id
    ON public.partner_settlement_allocations USING btree (company_id, lower(idempotency_key), created_at, id)
    WHERE (idempotency_key IS NOT NULL);
