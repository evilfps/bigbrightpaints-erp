-- Hard-cut policy: existing reset tokens have unknown delivery state, so invalidate them
-- instead of carrying ambiguous rows into the delivered-token restore flow.
DELETE FROM password_reset_tokens;

ALTER TABLE password_reset_tokens
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;
