ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS company_id BIGINT;

UPDATE app_users u
   SET company_id = uc.company_id
  FROM user_companies uc
 WHERE u.id = uc.user_id
   AND u.company_id IS NULL;

DO $$
DECLARE
    platform_scope_code VARCHAR(64);
BEGIN
    SELECT COALESCE(NULLIF(UPPER(TRIM(setting_value)), ''), 'PLATFORM')
      INTO platform_scope_code
      FROM system_settings
     WHERE setting_key = 'auth.platform.code';

    IF EXISTS (
        SELECT 1
          FROM app_users
         WHERE UPPER(TRIM(auth_scope_code)) = platform_scope_code
           AND company_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Platform-scoped auth accounts must not retain a tenant company binding.';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM app_users
         WHERE UPPER(TRIM(auth_scope_code)) <> platform_scope_code
           AND company_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'Tenant-scoped auth accounts must retain exactly one company binding.';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM app_users u
          JOIN companies c ON c.id = u.company_id
         WHERE UPPER(TRIM(u.auth_scope_code)) <> UPPER(TRIM(c.code))
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut found scoped accounts whose company_id does not match auth_scope_code.';
    END IF;
END $$;

ALTER TABLE app_users
    DROP CONSTRAINT IF EXISTS app_users_company_id_fkey;

ALTER TABLE app_users
    ADD CONSTRAINT app_users_company_id_fkey
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_app_users_company_id
    ON app_users (company_id);

DROP TABLE IF EXISTS user_companies;
