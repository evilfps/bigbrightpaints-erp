-- Flyway v2: cleanup redundant replay idempotency covering index.
-- V10 introduced this index for ordered idempotency scans; existing V8 indexes already
-- satisfy the case-insensitive key predicate, so we remove the redundant one to reduce write overhead.

DROP INDEX IF EXISTS public.idx_partner_settlement_idem_ci_created_id;
