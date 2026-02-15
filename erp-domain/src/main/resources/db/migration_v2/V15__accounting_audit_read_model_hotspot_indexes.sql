-- M16-S4 accounting audit read-model hotspot indexes.
-- Supports timeline list pagination and batched document/line lookups.
-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY idx_journal_entries_company_entry_date_id
    ON public.journal_entries USING btree (company_id, entry_date DESC, id DESC);

CREATE INDEX CONCURRENTLY idx_journal_lines_journal_entry_id
    ON public.journal_lines USING btree (journal_entry_id);

CREATE INDEX CONCURRENTLY idx_invoices_company_journal_entry
    ON public.invoices USING btree (company_id, journal_entry_id);

CREATE INDEX CONCURRENTLY idx_raw_material_purchases_company_journal_entry
    ON public.raw_material_purchases USING btree (company_id, journal_entry_id);
