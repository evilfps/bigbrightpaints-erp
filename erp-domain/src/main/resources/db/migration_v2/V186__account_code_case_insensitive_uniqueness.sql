-- Fail closed on company-scoped account-code duplicates regardless of case.
-- This aligns persisted account identity with the case-insensitive account resolution path.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.accounts
        GROUP BY company_id, lower(code)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Cannot enforce case-insensitive account-code uniqueness while case-variant duplicates exist in public.accounts'
            USING ERRCODE = '23505';
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_company_code_ci
    ON public.accounts USING btree (company_id, lower(code));
