-- Legacy bridge for canonical quota controls expected by SLICE-01 runtime.
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS quota_max_active_users BIGINT NOT NULL DEFAULT 0;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS quota_max_api_requests BIGINT NOT NULL DEFAULT 0;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS quota_max_storage_bytes BIGINT NOT NULL DEFAULT 0;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS quota_max_concurrent_sessions BIGINT NOT NULL DEFAULT 0;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS quota_soft_limit_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS quota_hard_limit_enabled BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'companies'
          AND c.conname = 'chk_companies_quota_max_active_users_non_negative'
    ) THEN
        ALTER TABLE companies
            DROP CONSTRAINT chk_companies_quota_max_active_users_non_negative;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'companies'
          AND c.conname = 'chk_companies_quota_max_api_requests_non_negative'
    ) THEN
        ALTER TABLE companies
            DROP CONSTRAINT chk_companies_quota_max_api_requests_non_negative;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'companies'
          AND c.conname = 'chk_companies_quota_max_storage_bytes_non_negative'
    ) THEN
        ALTER TABLE companies
            DROP CONSTRAINT chk_companies_quota_max_storage_bytes_non_negative;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'companies'
          AND c.conname = 'chk_companies_quota_max_concurrent_sessions_non_negative'
    ) THEN
        ALTER TABLE companies
            DROP CONSTRAINT chk_companies_quota_max_concurrent_sessions_non_negative;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'companies'
          AND c.conname = 'chk_companies_quota_enforcement_fail_closed'
    ) THEN
        ALTER TABLE companies
            DROP CONSTRAINT chk_companies_quota_enforcement_fail_closed;
    END IF;
END
$$;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_active_users_non_negative
        CHECK (quota_max_active_users >= 0);

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_api_requests_non_negative
        CHECK (quota_max_api_requests >= 0);

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_storage_bytes_non_negative
        CHECK (quota_max_storage_bytes >= 0);

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_concurrent_sessions_non_negative
        CHECK (quota_max_concurrent_sessions >= 0);

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_enforcement_fail_closed
        CHECK (quota_soft_limit_enabled OR quota_hard_limit_enabled);
