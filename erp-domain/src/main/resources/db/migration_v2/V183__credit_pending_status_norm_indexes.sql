-- Keep normalized pending-status predicates (upper(trim(status))) index-backed
-- for tenant-admin inbox and dashboard approval summary queries.

CREATE INDEX IF NOT EXISTS idx_credit_requests_company_status_norm_created_at
    ON public.credit_requests USING btree (company_id, upper(trim(status)), created_at DESC);

CREATE INDEX IF NOT EXISTS idx_credit_override_company_status_norm_created_at
    ON public.credit_limit_override_requests USING btree (company_id, upper(trim(status)), created_at DESC);
