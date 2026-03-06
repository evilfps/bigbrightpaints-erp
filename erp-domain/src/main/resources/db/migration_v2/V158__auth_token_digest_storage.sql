ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS token_digest VARCHAR(64);

ALTER TABLE refresh_tokens
    ALTER COLUMN token DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_refresh_tokens_token_digest
    ON refresh_tokens (token_digest)
    WHERE token_digest IS NOT NULL;

ALTER TABLE password_reset_tokens
    ADD COLUMN IF NOT EXISTS token_digest VARCHAR(64);

ALTER TABLE password_reset_tokens
    ALTER COLUMN token DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_password_reset_tokens_token_digest
    ON password_reset_tokens (token_digest)
    WHERE token_digest IS NOT NULL;
