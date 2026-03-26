ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_lifecycle_state;

UPDATE companies
SET lifecycle_state = 'SUSPENDED'
WHERE lifecycle_state = 'HOLD';

UPDATE companies
SET lifecycle_state = 'DEACTIVATED'
WHERE lifecycle_state = 'BLOCKED';

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_lifecycle_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'));

ALTER TABLE companies
    RENAME COLUMN quota_max_concurrent_sessions TO quota_max_concurrent_requests;

ALTER TABLE companies
    ADD COLUMN main_admin_user_id BIGINT REFERENCES app_users(id),
    ADD COLUMN support_notes TEXT,
    ADD COLUMN support_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN onboarding_coa_template_code VARCHAR(64),
    ADD COLUMN onboarding_admin_email VARCHAR(255),
    ADD COLUMN onboarding_admin_user_id BIGINT REFERENCES app_users(id),
    ADD COLUMN onboarding_completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN onboarding_credentials_emailed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_max_concurrent_sessions_non_negative;

ALTER TABLE companies
    DROP CONSTRAINT IF EXISTS chk_companies_quota_max_concurrent_requests_non_negative;

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_quota_max_concurrent_requests_non_negative
        CHECK (quota_max_concurrent_requests >= 0);

WITH ranked_admins AS (
    SELECT uc.company_id,
           u.id AS user_id,
           u.email,
           ROW_NUMBER() OVER (PARTITION BY uc.company_id ORDER BY u.id) AS admin_rank
    FROM user_companies uc
             JOIN app_users u ON u.id = uc.user_id
             JOIN user_roles ur ON ur.user_id = u.id
             JOIN roles r ON r.id = ur.role_id
    WHERE UPPER(r.name) IN ('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')
)
UPDATE companies c
SET main_admin_user_id = ra.user_id,
    onboarding_admin_user_id = COALESCE(c.onboarding_admin_user_id, ra.user_id),
    onboarding_admin_email = COALESCE(c.onboarding_admin_email, ra.email)
FROM ranked_admins ra
WHERE c.id = ra.company_id
  AND ra.admin_rank = 1
  AND c.main_admin_user_id IS NULL;

CREATE TABLE tenant_support_warnings (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    warning_category VARCHAR(100) NOT NULL,
    message VARCHAR(500) NOT NULL,
    requested_lifecycle_state VARCHAR(32) NOT NULL,
    grace_period_hours INTEGER NOT NULL,
    issued_by VARCHAR(255) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_tenant_support_warnings_company_issued_at
    ON tenant_support_warnings (company_id, issued_at DESC);

CREATE TABLE tenant_admin_email_change_requests (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    admin_user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    requested_by VARCHAR(255) NOT NULL,
    current_email VARCHAR(255) NOT NULL,
    requested_email VARCHAR(255) NOT NULL,
    verification_token VARCHAR(255) NOT NULL,
    verification_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_tenant_admin_email_change_requests_scope
    ON tenant_admin_email_change_requests (company_id, admin_user_id, consumed, id DESC);
