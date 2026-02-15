-- M16-S4 accounting audit read-model hotspot indexes.
-- Slice 2/4: journal line aggregation index.

CREATE INDEX IF NOT EXISTS idx_journal_lines_journal_entry_id
    ON public.journal_lines USING btree (journal_entry_id);
