-- Hard-cut database-enforced tenant isolation for accounting truth tables.
-- This migration introduces accounting RLS policies backed by the request-scoped
-- app.current_company_id setting.

CREATE OR REPLACE FUNCTION public.erp_current_company_id()
RETURNS BIGINT
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    raw_company_id TEXT;
BEGIN
    raw_company_id := NULLIF(current_setting('app.current_company_id', true), '');
    IF raw_company_id IS NULL THEN
        RETURN NULL;
    END IF;
    IF raw_company_id !~ '^[0-9]+$' THEN
        RETURN NULL;
    END IF;
    RETURN raw_company_id::BIGINT;
END;
$$;

CREATE OR REPLACE FUNCTION public.erp_accounting_tenant_visible(target_company_id BIGINT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT CASE
        WHEN public.erp_current_company_id() IS NULL THEN TRUE
        ELSE target_company_id = public.erp_current_company_id()
    END;
$$;

DO $$
DECLARE
    table_name TEXT;
    direct_company_tables TEXT[] := ARRAY[
        'accounts',
        'accounting_events',
        'accounting_periods',
        'accounting_period_snapshots',
        'journal_entries',
        'journal_reference_mappings',
        'dealer_ledger_entries',
        'supplier_ledger_entries',
        'partner_settlement_allocations',
        'opening_balance_imports',
        'tally_imports',
        'bank_reconciliation_sessions',
        'bank_reconciliation_items',
        'reconciliation_discrepancies',
        'period_close_requests',
        'closed_period_posting_exceptions'
    ];
BEGIN
    FOREACH table_name IN ARRAY direct_company_tables LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('DROP POLICY IF EXISTS rls_company_isolation ON public.%I', table_name);
        EXECUTE format(
            'CREATE POLICY rls_company_isolation ON public.%I
                 USING (public.erp_accounting_tenant_visible(company_id))
                 WITH CHECK (public.erp_accounting_tenant_visible(company_id))',
            table_name);
    END LOOP;
END;
$$;

ALTER TABLE public.journal_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.journal_lines FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_company_isolation ON public.journal_lines;
CREATE POLICY rls_company_isolation
ON public.journal_lines
USING (
    EXISTS (
        SELECT 1
        FROM public.journal_entries je
        WHERE je.id = journal_lines.journal_entry_id
          AND public.erp_accounting_tenant_visible(je.company_id)
    )
)
WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.journal_entries je
        WHERE je.id = journal_lines.journal_entry_id
          AND public.erp_accounting_tenant_visible(je.company_id)
    )
);

ALTER TABLE public.accounting_period_trial_balance_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_period_trial_balance_lines FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_company_isolation ON public.accounting_period_trial_balance_lines;
CREATE POLICY rls_company_isolation
ON public.accounting_period_trial_balance_lines
USING (
    EXISTS (
        SELECT 1
        FROM public.accounting_period_snapshots aps
        WHERE aps.id = accounting_period_trial_balance_lines.snapshot_id
          AND public.erp_accounting_tenant_visible(aps.company_id)
    )
)
WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.accounting_period_snapshots aps
        WHERE aps.id = accounting_period_trial_balance_lines.snapshot_id
          AND public.erp_accounting_tenant_visible(aps.company_id)
    )
);
