-- Hard-cut dealer receipt and hybrid receipt onto explicit payment-event truth.
-- Adds partner payment events and links allocation rows to the originating payment event.

CREATE TABLE IF NOT EXISTS public.partner_payment_events (
    id bigint NOT NULL,
    public_id uuid NOT NULL,
    company_id bigint NOT NULL,
    partner_type character varying(16) NOT NULL,
    dealer_id bigint,
    supplier_id bigint,
    payment_flow character varying(32) NOT NULL,
    source_route character varying(128) NOT NULL,
    reference_number character varying(128) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    payment_date date NOT NULL,
    amount numeric(18,2) DEFAULT 0 NOT NULL,
    currency character varying(16) DEFAULT 'INR'::character varying NOT NULL,
    memo text,
    journal_entry_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    posted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_partner_payment_events_partner
        CHECK (((partner_type)::text = 'DEALER'::text AND dealer_id IS NOT NULL AND supplier_id IS NULL)
            OR ((partner_type)::text = 'SUPPLIER'::text AND supplier_id IS NOT NULL AND dealer_id IS NULL))
);

CREATE SEQUENCE IF NOT EXISTS public.partner_payment_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.partner_payment_events_id_seq OWNED BY public.partner_payment_events.id;

ALTER TABLE ONLY public.partner_payment_events
    ALTER COLUMN id SET DEFAULT nextval('public.partner_payment_events_id_seq'::regclass);

ALTER TABLE ONLY public.partner_payment_events
    DROP CONSTRAINT IF EXISTS partner_payment_events_pkey;
ALTER TABLE ONLY public.partner_payment_events
    ADD CONSTRAINT partner_payment_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.partner_payment_events
    DROP CONSTRAINT IF EXISTS partner_payment_events_public_id_key;
ALTER TABLE ONLY public.partner_payment_events
    ADD CONSTRAINT partner_payment_events_public_id_key UNIQUE (public_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_partner_payment_events_company_flow_reference
    ON public.partner_payment_events USING btree (company_id, payment_flow, reference_number);

CREATE INDEX IF NOT EXISTS idx_partner_payment_events_company
    ON public.partner_payment_events USING btree (company_id);

CREATE INDEX IF NOT EXISTS idx_partner_payment_events_partner_date
    ON public.partner_payment_events USING btree (company_id, partner_type, dealer_id, supplier_id, payment_date);

CREATE INDEX IF NOT EXISTS idx_partner_payment_events_idempotency_ci
    ON public.partner_payment_events USING btree (company_id, lower(idempotency_key));

CREATE INDEX IF NOT EXISTS idx_partner_payment_events_journal_entry
    ON public.partner_payment_events USING btree (company_id, journal_entry_id);

ALTER TABLE ONLY public.partner_payment_events
    DROP CONSTRAINT IF EXISTS partner_payment_events_company_id_fkey;
ALTER TABLE ONLY public.partner_payment_events
    ADD CONSTRAINT partner_payment_events_company_id_fkey
        FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.partner_payment_events
    DROP CONSTRAINT IF EXISTS partner_payment_events_dealer_id_fkey;
ALTER TABLE ONLY public.partner_payment_events
    ADD CONSTRAINT partner_payment_events_dealer_id_fkey
        FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.partner_payment_events
    DROP CONSTRAINT IF EXISTS partner_payment_events_supplier_id_fkey;
ALTER TABLE ONLY public.partner_payment_events
    ADD CONSTRAINT partner_payment_events_supplier_id_fkey
        FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.partner_payment_events
    DROP CONSTRAINT IF EXISTS partner_payment_events_journal_entry_id_fkey;
ALTER TABLE ONLY public.partner_payment_events
    ADD CONSTRAINT partner_payment_events_journal_entry_id_fkey
        FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.partner_settlement_allocations
    ADD COLUMN IF NOT EXISTS payment_event_id bigint;

CREATE INDEX IF NOT EXISTS idx_partner_settlement_payment_event
    ON public.partner_settlement_allocations USING btree (company_id, payment_event_id);

ALTER TABLE ONLY public.partner_settlement_allocations
    DROP CONSTRAINT IF EXISTS partner_settlement_allocations_payment_event_id_fkey;
ALTER TABLE ONLY public.partner_settlement_allocations
    ADD CONSTRAINT partner_settlement_allocations_payment_event_id_fkey
        FOREIGN KEY (payment_event_id) REFERENCES public.partner_payment_events(id) ON DELETE SET NULL;

ALTER TABLE public.partner_payment_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.partner_payment_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_company_isolation ON public.partner_payment_events;
CREATE POLICY rls_company_isolation
ON public.partner_payment_events
USING (public.erp_accounting_tenant_visible(company_id))
WITH CHECK (public.erp_accounting_tenant_visible(company_id));
