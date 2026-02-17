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

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_max_active_users_non_negative;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_active_users_non_negative
        CHECK (quota_max_active_users >= 0);

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_max_api_requests_non_negative;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_api_requests_non_negative
        CHECK (quota_max_api_requests >= 0);

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_max_storage_bytes_non_negative;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_storage_bytes_non_negative
        CHECK (quota_max_storage_bytes >= 0);

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_max_concurrent_sessions_non_negative;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_concurrent_sessions_non_negative
        CHECK (quota_max_concurrent_sessions >= 0);

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_enforcement_fail_closed;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_enforcement_fail_closed
        CHECK (quota_soft_limit_enabled OR quota_hard_limit_enabled);
