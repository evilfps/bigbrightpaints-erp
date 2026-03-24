ALTER TABLE public.credit_requests
    ADD COLUMN IF NOT EXISTS requester_user_id BIGINT;

ALTER TABLE public.credit_requests
    ADD COLUMN IF NOT EXISTS requester_email character varying(255);
