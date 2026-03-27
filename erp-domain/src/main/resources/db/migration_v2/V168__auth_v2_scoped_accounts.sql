INSERT INTO system_settings (setting_key, setting_value)
VALUES ('auth.platform.code', 'PLATFORM')
ON CONFLICT (setting_key) DO NOTHING;

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
          FROM companies
         WHERE UPPER(TRIM(code)) = platform_scope_code
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut requires auth.platform.code to stay unique from tenant company codes. Rename the platform auth code or the conflicting company code before rerunning migration.';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM user_companies uc
          JOIN user_roles ur ON ur.user_id = uc.user_id
          JOIN roles r ON r.id = ur.role_id
         WHERE UPPER(r.name) = 'ROLE_SUPER_ADMIN'
    ) THEN
        DELETE FROM user_companies uc
         USING user_roles ur, roles r
         WHERE uc.user_id = ur.user_id
           AND ur.role_id = r.id
           AND UPPER(r.name) = 'ROLE_SUPER_ADMIN';
    END IF;

    IF EXISTS (
        SELECT uc.user_id
          FROM user_companies uc
      GROUP BY uc.user_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut blocks legacy multi-company user memberships. Recreate those accounts as one scoped account per company before rerunning migration.';
    END IF;
END $$;

ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS auth_scope_code VARCHAR(64);

UPDATE app_users u
   SET auth_scope_code = UPPER(TRIM(c.code))
  FROM user_companies uc
  JOIN companies c ON c.id = uc.company_id
 WHERE u.id = uc.user_id
   AND COALESCE(TRIM(u.auth_scope_code), '') = '';

UPDATE app_users u
   SET auth_scope_code = (
       SELECT COALESCE(NULLIF(UPPER(TRIM(setting_value)), ''), 'PLATFORM')
         FROM system_settings
        WHERE setting_key = 'auth.platform.code'
   )
 WHERE COALESCE(TRIM(u.auth_scope_code), '') = ''
   AND EXISTS (
       SELECT 1
         FROM user_roles ur
         JOIN roles r ON r.id = ur.role_id
        WHERE ur.user_id = u.id
          AND UPPER(r.name) = 'ROLE_SUPER_ADMIN'
   );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM app_users
         WHERE COALESCE(TRIM(auth_scope_code), '') = ''
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut found user accounts without a scoped auth target. Every non-platform user must belong to exactly one company.';
    END IF;
END $$;

UPDATE app_users
   SET auth_scope_code = UPPER(TRIM(auth_scope_code));

ALTER TABLE app_users
    ALTER COLUMN auth_scope_code SET NOT NULL;

ALTER TABLE app_users
    DROP CONSTRAINT IF EXISTS app_users_email_key;

UPDATE app_users
   SET email = LOWER(TRIM(email));

DO $$
BEGIN
    IF EXISTS (
        SELECT LOWER(TRIM(email)) AS normalized_email, auth_scope_code
          FROM app_users
      GROUP BY LOWER(TRIM(email)), auth_scope_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut found same-scope accounts that collide after email normalization. Resolve duplicate emails before rerunning migration.';
    END IF;
END $$;

ALTER TABLE app_users
    ADD CONSTRAINT uq_app_users_email_scope UNIQUE (email, auth_scope_code);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_companies_single_company_per_user
    ON user_companies (user_id);

ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS user_public_id UUID,
    ADD COLUMN IF NOT EXISTS auth_scope_code VARCHAR(64);

DO $$
BEGIN
    IF EXISTS (
        SELECT LOWER(TRIM(rt.user_email)) AS normalized_legacy_email
          FROM refresh_tokens rt
          JOIN app_users u ON u.email = LOWER(TRIM(rt.user_email))
         WHERE rt.user_public_id IS NULL
      GROUP BY LOWER(TRIM(rt.user_email))
        HAVING COUNT(DISTINCT u.id) > 1
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut found ambiguous legacy refresh tokens after scoped auth split. Clear or rotate those refresh tokens before rerunning migration.';
    END IF;
END $$;

UPDATE refresh_tokens rt
   SET user_public_id = u.public_id,
       auth_scope_code = u.auth_scope_code
  FROM app_users u
 WHERE rt.user_public_id IS NULL
   AND u.email = LOWER(TRIM(rt.user_email));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM refresh_tokens
         WHERE user_public_id IS NULL
            OR COALESCE(TRIM(auth_scope_code), '') = ''
    ) THEN
        RAISE EXCEPTION
            'Auth V2 hard cut could not map one or more refresh tokens to scoped accounts. Clear legacy refresh tokens before rerunning migration.';
    END IF;
END $$;

ALTER TABLE refresh_tokens
    ALTER COLUMN user_public_id SET NOT NULL,
    ALTER COLUMN auth_scope_code SET NOT NULL;

DROP INDEX IF EXISTS idx_refresh_tokens_user_email;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_public_id
    ON refresh_tokens (user_public_id);

ALTER TABLE refresh_tokens
    DROP COLUMN IF EXISTS user_email;
