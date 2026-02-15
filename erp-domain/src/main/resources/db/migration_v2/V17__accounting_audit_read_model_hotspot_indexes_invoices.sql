-- M16-S4 accounting audit read-model hotspot indexes.
-- Slice 3/4: invoice linkage lookup index.

CREATE INDEX IF NOT EXISTS idx_invoices_company_journal_entry
    ON public.invoices USING btree (company_id, journal_entry_id);
