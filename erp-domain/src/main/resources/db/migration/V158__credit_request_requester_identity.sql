ALTER TABLE credit_requests
    ADD COLUMN IF NOT EXISTS requester_user_id BIGINT;

ALTER TABLE credit_requests
    ADD COLUMN IF NOT EXISTS requester_email VARCHAR(255);
