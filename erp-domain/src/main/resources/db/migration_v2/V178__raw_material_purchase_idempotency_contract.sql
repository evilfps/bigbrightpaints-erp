-- Flyway v2: canonical idempotency replay support for purchase invoice writes.

ALTER TABLE public.raw_material_purchases
    ADD COLUMN IF NOT EXISTS idempotency_key character varying(128);

ALTER TABLE public.raw_material_purchases
    ADD COLUMN IF NOT EXISTS idempotency_hash character varying(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_material_purchases_company_idempotency
    ON public.raw_material_purchases USING btree (company_id, idempotency_key)
    WHERE (idempotency_key IS NOT NULL);
