-- Tighten accounting RLS tenant binding:
-- 1) accept CompanyContextHolder-bound company codes on app.current_company_id
-- 2) fail closed when tenant session context is missing, malformed, or unmapped

CREATE OR REPLACE FUNCTION public.erp_current_company_id()
RETURNS BIGINT
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    raw_company_context TEXT;
    resolved_company_id BIGINT;
BEGIN
    raw_company_context := NULLIF(current_setting('app.current_company_id', true), '');
    IF raw_company_context IS NULL THEN
        RETURN NULL;
    END IF;

    IF raw_company_context ~ '^[0-9]+$' THEN
        RETURN raw_company_context::BIGINT;
    END IF;

    IF raw_company_context !~ '^[A-Za-z0-9_.-]+$' THEN
        RETURN NULL;
    END IF;

    SELECT c.id
      INTO resolved_company_id
      FROM public.companies c
     WHERE lower(c.code) = lower(raw_company_context)
     LIMIT 1;

    RETURN resolved_company_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.erp_accounting_tenant_visible(target_company_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    session_company_id BIGINT;
BEGIN
    session_company_id := public.erp_current_company_id();
    IF session_company_id IS NULL OR target_company_id IS NULL THEN
        RETURN FALSE;
    END IF;
    RETURN target_company_id = session_company_id;
END;
$$;
