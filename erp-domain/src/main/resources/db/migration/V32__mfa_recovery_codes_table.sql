-- Create a proper table for MFA recovery codes
CREATE TABLE IF NOT EXISTS mfa_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Partial unique index for fast lookup by user and unused codes
CREATE UNIQUE INDEX idx_mfa_recovery_user_id_unused
    ON mfa_recovery_codes(user_id, code_hash)
    WHERE used_at IS NULL;

-- Index for finding user's recovery codes
CREATE INDEX idx_mfa_recovery_codes_user_id ON mfa_recovery_codes(user_id);

-- Index for cleanup of old used codes
CREATE INDEX idx_mfa_recovery_codes_used_at ON mfa_recovery_codes(used_at) WHERE used_at IS NOT NULL;

-- Migrate existing recovery codes from the comma-delimited string
-- This is a one-time migration to move data from the old format
DO $$
DECLARE
    user_record RECORD;
    code_hash TEXT;
    codes_array TEXT[];
BEGIN
    FOR user_record IN
        SELECT id, mfa_recovery_codes
        FROM app_users
        WHERE mfa_recovery_codes IS NOT NULL
          AND mfa_recovery_codes != ''
    LOOP
        -- Split the comma-delimited string into an array
        codes_array := string_to_array(user_record.mfa_recovery_codes, ',');

        -- Insert each code into the new table
        FOREACH code_hash IN ARRAY codes_array
        LOOP
            IF code_hash IS NOT NULL AND trim(code_hash) != '' THEN
                INSERT INTO mfa_recovery_codes (user_id, code_hash, created_at)
                VALUES (user_record.id, trim(code_hash), CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Drop the old column after migration (commented out for safety - run manually after verification)
-- ALTER TABLE app_users DROP COLUMN mfa_recovery_codes;

-- Add a comment to document the table
COMMENT ON TABLE mfa_recovery_codes IS 'Stores MFA recovery codes with proper relational structure and usage tracking';
COMMENT ON COLUMN mfa_recovery_codes.code_hash IS 'BCrypt hash of the recovery code';
COMMENT ON COLUMN mfa_recovery_codes.used_at IS 'Timestamp when the code was used, NULL if unused';