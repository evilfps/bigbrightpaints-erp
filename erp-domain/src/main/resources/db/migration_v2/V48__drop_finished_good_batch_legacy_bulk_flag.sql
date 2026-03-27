-- ERP-23 hard cut: remove retired finished_goods bulk flag model.
DROP INDEX IF EXISTS public.idx_fg_batch_bulk;

ALTER TABLE public.finished_good_batches
    DROP COLUMN IF EXISTS is_bulk;
