-- M16-S4 accounting audit read-model hotspot indexes.
-- Slice 4/4: purchase linkage lookup index.

CREATE INDEX IF NOT EXISTS idx_raw_material_purchases_company_journal_entry
    ON public.raw_material_purchases USING btree (company_id, journal_entry_id);
