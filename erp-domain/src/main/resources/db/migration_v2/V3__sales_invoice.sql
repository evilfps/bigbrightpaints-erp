-- Flyway v2: sales/invoice/dealer baseline
-- Generated from canonical schema snapshot and grouped by domain phase.

-- TABLE: credit_limit_override_requests
CREATE TABLE public.credit_limit_override_requests (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    dealer_id bigint,
    packaging_slip_id bigint,
    sales_order_id bigint,
    dispatch_amount numeric(18,2) NOT NULL,
    current_exposure numeric(18,2) DEFAULT 0 NOT NULL,
    credit_limit numeric(18,2) DEFAULT 0 NOT NULL,
    required_headroom numeric(18,2) DEFAULT 0 NOT NULL,
    status character varying(32) NOT NULL,
    reason text,
    requested_by character varying(255),
    reviewed_by character varying(255),
    reviewed_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: credit_limit_override_requests_id_seq
CREATE SEQUENCE public.credit_limit_override_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: credit_limit_override_requests_id_seq
ALTER SEQUENCE public.credit_limit_override_requests_id_seq OWNED BY public.credit_limit_override_requests.id;

-- TABLE: credit_requests
CREATE TABLE public.credit_requests (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    dealer_id bigint,
    amount_requested numeric(18,2) NOT NULL,
    status character varying(32) NOT NULL,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: credit_requests_id_seq
CREATE SEQUENCE public.credit_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: credit_requests_id_seq
ALTER SEQUENCE public.credit_requests_id_seq OWNED BY public.credit_requests.id;

-- TABLE: dealers
CREATE TABLE public.dealers (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    code character varying(64) NOT NULL,
    email character varying(255),
    phone character varying(64),
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    credit_limit numeric(18,2) DEFAULT 0 NOT NULL,
    outstanding_balance numeric(18,2) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    address text,
    portal_user_id bigint,
    receivable_account_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    company_name character varying(255)
);

-- SEQUENCE: dealers_id_seq
CREATE SEQUENCE public.dealers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: dealers_id_seq
ALTER SEQUENCE public.dealers_id_seq OWNED BY public.dealers.id;

-- TABLE: invoice_lines
CREATE TABLE public.invoice_lines (
    id bigint NOT NULL,
    invoice_id bigint NOT NULL,
    product_code character varying(128),
    description character varying(255),
    quantity numeric(18,3) NOT NULL,
    unit_price numeric(18,2) NOT NULL,
    tax_rate numeric(5,2) DEFAULT 0 NOT NULL,
    line_total numeric(18,2) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    taxable_amount numeric(18,2),
    tax_amount numeric(18,2),
    discount_amount numeric(18,2)
);

-- SEQUENCE: invoice_lines_id_seq
CREATE SEQUENCE public.invoice_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: invoice_lines_id_seq
ALTER SEQUENCE public.invoice_lines_id_seq OWNED BY public.invoice_lines.id;

-- TABLE: invoice_payment_refs
CREATE TABLE public.invoice_payment_refs (
    invoice_id bigint NOT NULL,
    payment_reference character varying(255) NOT NULL
);

-- TABLE: invoice_sequences
CREATE TABLE public.invoice_sequences (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    fiscal_year integer NOT NULL,
    next_number bigint DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: invoice_sequences_id_seq
CREATE SEQUENCE public.invoice_sequences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: invoice_sequences_id_seq
ALTER SEQUENCE public.invoice_sequences_id_seq OWNED BY public.invoice_sequences.id;

-- TABLE: invoices
CREATE TABLE public.invoices (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    dealer_id bigint NOT NULL,
    sales_order_id bigint,
    invoice_number character varying(64) NOT NULL,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    subtotal numeric(18,2) DEFAULT 0 NOT NULL,
    tax_total numeric(18,2) DEFAULT 0 NOT NULL,
    total_amount numeric(18,2) DEFAULT 0 NOT NULL,
    currency character varying(16) DEFAULT 'INR'::character varying NOT NULL,
    issue_date date DEFAULT CURRENT_DATE NOT NULL,
    due_date date,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    journal_entry_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    outstanding_amount numeric(18,2) DEFAULT 0 NOT NULL
);

-- SEQUENCE: invoices_id_seq
CREATE SEQUENCE public.invoices_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: invoices_id_seq
ALTER SEQUENCE public.invoices_id_seq OWNED BY public.invoices.id;

-- TABLE: order_sequences
CREATE TABLE public.order_sequences (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    fiscal_year integer NOT NULL,
    next_number bigint DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: order_sequences_id_seq
CREATE SEQUENCE public.order_sequences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: order_sequences_id_seq
ALTER SEQUENCE public.order_sequences_id_seq OWNED BY public.order_sequences.id;

-- TABLE: packaging_slip_lines
CREATE TABLE public.packaging_slip_lines (
    id bigint NOT NULL,
    packaging_slip_id bigint NOT NULL,
    finished_good_batch_id bigint NOT NULL,
    quantity numeric(18,3) NOT NULL,
    unit_cost numeric(18,4) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    ordered_quantity numeric(19,4) NOT NULL,
    shipped_quantity numeric(19,4),
    backorder_quantity numeric(19,4),
    notes character varying(500)
);

-- SEQUENCE: packaging_slip_lines_id_seq
CREATE SEQUENCE public.packaging_slip_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: packaging_slip_lines_id_seq
ALTER SEQUENCE public.packaging_slip_lines_id_seq OWNED BY public.packaging_slip_lines.id;

-- TABLE: packaging_slips
CREATE TABLE public.packaging_slips (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    sales_order_id bigint NOT NULL,
    slip_number character varying(64) NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    dispatched_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    confirmed_at timestamp without time zone,
    confirmed_by character varying(255),
    dispatch_notes character varying(1000),
    journal_entry_id bigint,
    cogs_journal_entry_id bigint,
    invoice_id bigint,
    is_backorder boolean DEFAULT false NOT NULL
);

-- SEQUENCE: packaging_slips_id_seq
CREATE SEQUENCE public.packaging_slips_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: packaging_slips_id_seq
ALTER SEQUENCE public.packaging_slips_id_seq OWNED BY public.packaging_slips.id;

-- TABLE: promotions
CREATE TABLE public.promotions (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    discount_type character varying(32) NOT NULL,
    discount_value numeric(10,2) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: promotions_id_seq
CREATE SEQUENCE public.promotions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: promotions_id_seq
ALTER SEQUENCE public.promotions_id_seq OWNED BY public.promotions.id;

-- TABLE: sales_order_items
CREATE TABLE public.sales_order_items (
    id bigint NOT NULL,
    sales_order_id bigint NOT NULL,
    product_code character varying(128) NOT NULL,
    description character varying(255),
    quantity numeric(18,3) NOT NULL,
    unit_price numeric(18,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    line_subtotal numeric(18,2) DEFAULT 0 NOT NULL,
    line_total numeric(18,2) DEFAULT 0 NOT NULL,
    gst_rate numeric(7,4) DEFAULT 0 NOT NULL,
    gst_amount numeric(18,2) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: sales_order_items_id_seq
CREATE SEQUENCE public.sales_order_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: sales_order_items_id_seq
ALTER SEQUENCE public.sales_order_items_id_seq OWNED BY public.sales_order_items.id;

-- TABLE: sales_orders
CREATE TABLE public.sales_orders (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    dealer_id bigint,
    order_number character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    total_amount numeric(18,2) NOT NULL,
    currency character varying(16) DEFAULT 'INR'::character varying NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    trace_id character varying(64),
    subtotal_amount numeric(18,2) DEFAULT 0 NOT NULL,
    gst_total numeric(18,2) DEFAULT 0 NOT NULL,
    gst_treatment character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    gst_rate numeric(7,4),
    gst_rounding_adjustment numeric(18,2) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    idempotency_key character varying(255),
    sales_journal_entry_id bigint,
    cogs_journal_entry_id bigint,
    fulfillment_invoice_id bigint,
    idempotency_hash character varying(64),
    gst_inclusive boolean DEFAULT false NOT NULL
);

-- SEQUENCE: sales_orders_id_seq
CREATE SEQUENCE public.sales_orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: sales_orders_id_seq
ALTER SEQUENCE public.sales_orders_id_seq OWNED BY public.sales_orders.id;

-- TABLE: sales_targets
CREATE TABLE public.sales_targets (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    target_amount numeric(18,2) NOT NULL,
    achieved_amount numeric(18,2) DEFAULT 0 NOT NULL,
    assignee character varying(255),
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: sales_targets_id_seq
CREATE SEQUENCE public.sales_targets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: sales_targets_id_seq
ALTER SEQUENCE public.sales_targets_id_seq OWNED BY public.sales_targets.id;

-- DEFAULT: credit_limit_override_requests id
ALTER TABLE ONLY public.credit_limit_override_requests ALTER COLUMN id SET DEFAULT nextval('public.credit_limit_override_requests_id_seq'::regclass);

-- DEFAULT: credit_requests id
ALTER TABLE ONLY public.credit_requests ALTER COLUMN id SET DEFAULT nextval('public.credit_requests_id_seq'::regclass);

-- DEFAULT: dealers id
ALTER TABLE ONLY public.dealers ALTER COLUMN id SET DEFAULT nextval('public.dealers_id_seq'::regclass);

-- DEFAULT: invoice_lines id
ALTER TABLE ONLY public.invoice_lines ALTER COLUMN id SET DEFAULT nextval('public.invoice_lines_id_seq'::regclass);

-- DEFAULT: invoice_sequences id
ALTER TABLE ONLY public.invoice_sequences ALTER COLUMN id SET DEFAULT nextval('public.invoice_sequences_id_seq'::regclass);

-- DEFAULT: invoices id
ALTER TABLE ONLY public.invoices ALTER COLUMN id SET DEFAULT nextval('public.invoices_id_seq'::regclass);

-- DEFAULT: order_sequences id
ALTER TABLE ONLY public.order_sequences ALTER COLUMN id SET DEFAULT nextval('public.order_sequences_id_seq'::regclass);

-- DEFAULT: packaging_slip_lines id
ALTER TABLE ONLY public.packaging_slip_lines ALTER COLUMN id SET DEFAULT nextval('public.packaging_slip_lines_id_seq'::regclass);

-- DEFAULT: packaging_slips id
ALTER TABLE ONLY public.packaging_slips ALTER COLUMN id SET DEFAULT nextval('public.packaging_slips_id_seq'::regclass);

-- DEFAULT: promotions id
ALTER TABLE ONLY public.promotions ALTER COLUMN id SET DEFAULT nextval('public.promotions_id_seq'::regclass);

-- DEFAULT: sales_order_items id
ALTER TABLE ONLY public.sales_order_items ALTER COLUMN id SET DEFAULT nextval('public.sales_order_items_id_seq'::regclass);

-- DEFAULT: sales_orders id
ALTER TABLE ONLY public.sales_orders ALTER COLUMN id SET DEFAULT nextval('public.sales_orders_id_seq'::regclass);

-- DEFAULT: sales_targets id
ALTER TABLE ONLY public.sales_targets ALTER COLUMN id SET DEFAULT nextval('public.sales_targets_id_seq'::regclass);

-- CONSTRAINT: credit_limit_override_requests credit_limit_override_requests_pkey
ALTER TABLE ONLY public.credit_limit_override_requests
    ADD CONSTRAINT credit_limit_override_requests_pkey PRIMARY KEY (id);

-- CONSTRAINT: credit_requests credit_requests_pkey
ALTER TABLE ONLY public.credit_requests
    ADD CONSTRAINT credit_requests_pkey PRIMARY KEY (id);

-- CONSTRAINT: dealers dealers_company_id_code_key
ALTER TABLE ONLY public.dealers
    ADD CONSTRAINT dealers_company_id_code_key UNIQUE (company_id, code);

-- CONSTRAINT: dealers dealers_pkey
ALTER TABLE ONLY public.dealers
    ADD CONSTRAINT dealers_pkey PRIMARY KEY (id);

-- CONSTRAINT: invoice_lines invoice_lines_pkey
ALTER TABLE ONLY public.invoice_lines
    ADD CONSTRAINT invoice_lines_pkey PRIMARY KEY (id);

-- CONSTRAINT: invoice_payment_refs invoice_payment_refs_pkey
ALTER TABLE ONLY public.invoice_payment_refs
    ADD CONSTRAINT invoice_payment_refs_pkey PRIMARY KEY (invoice_id, payment_reference);

-- CONSTRAINT: invoice_sequences invoice_sequences_company_id_fiscal_year_key
ALTER TABLE ONLY public.invoice_sequences
    ADD CONSTRAINT invoice_sequences_company_id_fiscal_year_key UNIQUE (company_id, fiscal_year);

-- CONSTRAINT: invoice_sequences invoice_sequences_pkey
ALTER TABLE ONLY public.invoice_sequences
    ADD CONSTRAINT invoice_sequences_pkey PRIMARY KEY (id);

-- CONSTRAINT: invoices invoices_company_id_invoice_number_key
ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_company_id_invoice_number_key UNIQUE (company_id, invoice_number);

-- CONSTRAINT: invoices invoices_pkey
ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);

-- CONSTRAINT: order_sequences order_sequences_company_id_fiscal_year_key
ALTER TABLE ONLY public.order_sequences
    ADD CONSTRAINT order_sequences_company_id_fiscal_year_key UNIQUE (company_id, fiscal_year);

-- CONSTRAINT: order_sequences order_sequences_pkey
ALTER TABLE ONLY public.order_sequences
    ADD CONSTRAINT order_sequences_pkey PRIMARY KEY (id);

-- CONSTRAINT: packaging_slip_lines packaging_slip_lines_pkey
ALTER TABLE ONLY public.packaging_slip_lines
    ADD CONSTRAINT packaging_slip_lines_pkey PRIMARY KEY (id);

-- CONSTRAINT: packaging_slips packaging_slips_company_id_slip_number_key
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT packaging_slips_company_id_slip_number_key UNIQUE (company_id, slip_number);

-- CONSTRAINT: packaging_slips packaging_slips_pkey
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT packaging_slips_pkey PRIMARY KEY (id);

-- CONSTRAINT: promotions promotions_pkey
ALTER TABLE ONLY public.promotions
    ADD CONSTRAINT promotions_pkey PRIMARY KEY (id);

-- CONSTRAINT: sales_order_items sales_order_items_pkey
ALTER TABLE ONLY public.sales_order_items
    ADD CONSTRAINT sales_order_items_pkey PRIMARY KEY (id);

-- CONSTRAINT: sales_orders sales_orders_company_id_order_number_key
ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT sales_orders_company_id_order_number_key UNIQUE (company_id, order_number);

-- CONSTRAINT: sales_orders sales_orders_pkey
ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT sales_orders_pkey PRIMARY KEY (id);

-- CONSTRAINT: sales_targets sales_targets_pkey
ALTER TABLE ONLY public.sales_targets
    ADD CONSTRAINT sales_targets_pkey PRIMARY KEY (id);

-- CONSTRAINT: packaging_slips uk_packaging_slips_cogs_journal_entry_id
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT uk_packaging_slips_cogs_journal_entry_id UNIQUE (cogs_journal_entry_id);

-- CONSTRAINT: packaging_slips uk_packaging_slips_journal_entry_id
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT uk_packaging_slips_journal_entry_id UNIQUE (journal_entry_id);

-- INDEX: idx_credit_override_company_status
CREATE INDEX idx_credit_override_company_status ON public.credit_limit_override_requests USING btree (company_id, status);

-- INDEX: idx_credit_override_packaging_slip
CREATE INDEX idx_credit_override_packaging_slip ON public.credit_limit_override_requests USING btree (packaging_slip_id);

-- INDEX: idx_credit_override_sales_order
CREATE INDEX idx_credit_override_sales_order ON public.credit_limit_override_requests USING btree (sales_order_id);

-- INDEX: idx_dealers_portal_user
CREATE INDEX idx_dealers_portal_user ON public.dealers USING btree (portal_user_id);

-- INDEX: idx_dealers_receivable_account
CREATE INDEX idx_dealers_receivable_account ON public.dealers USING btree (receivable_account_id);

-- INDEX: idx_invoice_payment_refs_invoice_id
CREATE INDEX idx_invoice_payment_refs_invoice_id ON public.invoice_payment_refs USING btree (invoice_id);

-- INDEX: idx_invoices_company_dealer_issue_date
CREATE INDEX idx_invoices_company_dealer_issue_date ON public.invoices USING btree (company_id, dealer_id, issue_date DESC, id DESC);

-- INDEX: idx_invoices_company_issue_date
CREATE INDEX idx_invoices_company_issue_date ON public.invoices USING btree (company_id, issue_date DESC, id DESC);

-- INDEX: idx_invoices_dealer
CREATE INDEX idx_invoices_dealer ON public.invoices USING btree (dealer_id);

-- INDEX: idx_invoices_journal_entry
CREATE INDEX idx_invoices_journal_entry ON public.invoices USING btree (journal_entry_id);

-- INDEX: idx_invoices_order
CREATE INDEX idx_invoices_order ON public.invoices USING btree (sales_order_id);

-- INDEX: idx_invoices_status
CREATE INDEX idx_invoices_status ON public.invoices USING btree (status);

-- INDEX: idx_packaging_slips_confirmed_at
CREATE INDEX idx_packaging_slips_confirmed_at ON public.packaging_slips USING btree (confirmed_at);

-- INDEX: idx_packaging_slips_order
CREATE INDEX idx_packaging_slips_order ON public.packaging_slips USING btree (sales_order_id);

-- INDEX: idx_packaging_slips_status
CREATE INDEX idx_packaging_slips_status ON public.packaging_slips USING btree (status);

-- INDEX: idx_sales_order_items_order
CREATE INDEX idx_sales_order_items_order ON public.sales_order_items USING btree (sales_order_id);

-- INDEX: idx_sales_orders_cogs_journal
CREATE INDEX idx_sales_orders_cogs_journal ON public.sales_orders USING btree (cogs_journal_entry_id) WHERE (cogs_journal_entry_id IS NOT NULL);

-- INDEX: idx_sales_orders_company_created_at
CREATE INDEX idx_sales_orders_company_created_at ON public.sales_orders USING btree (company_id, created_at DESC, id DESC);

-- INDEX: idx_sales_orders_company_status_created_at
CREATE INDEX idx_sales_orders_company_status_created_at ON public.sales_orders USING btree (company_id, status, created_at DESC, id DESC);

-- INDEX: idx_sales_orders_created_at
CREATE INDEX idx_sales_orders_created_at ON public.sales_orders USING btree (created_at);

-- INDEX: idx_sales_orders_dealer
CREATE INDEX idx_sales_orders_dealer ON public.sales_orders USING btree (dealer_id);

-- INDEX: idx_sales_orders_fulfillment_invoice
CREATE INDEX idx_sales_orders_fulfillment_invoice ON public.sales_orders USING btree (fulfillment_invoice_id) WHERE (fulfillment_invoice_id IS NOT NULL);

-- INDEX: idx_sales_orders_idempotency
CREATE UNIQUE INDEX idx_sales_orders_idempotency ON public.sales_orders USING btree (company_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);

-- INDEX: idx_sales_orders_sales_journal
CREATE INDEX idx_sales_orders_sales_journal ON public.sales_orders USING btree (sales_journal_entry_id) WHERE (sales_journal_entry_id IS NOT NULL);

-- INDEX: idx_sales_orders_status
CREATE INDEX idx_sales_orders_status ON public.sales_orders USING btree (status);

-- INDEX: uq_packaging_slips_invoice_id
CREATE UNIQUE INDEX uq_packaging_slips_invoice_id ON public.packaging_slips USING btree (invoice_id) WHERE (invoice_id IS NOT NULL);

-- INDEX: uq_packaging_slips_order_backorder_active
CREATE UNIQUE INDEX uq_packaging_slips_order_backorder_active ON public.packaging_slips USING btree (company_id, sales_order_id) WHERE ((is_backorder = true) AND (upper((status)::text) = 'BACKORDER'::text));

-- INDEX: uq_packaging_slips_order_primary_active
CREATE UNIQUE INDEX uq_packaging_slips_order_primary_active ON public.packaging_slips USING btree (company_id, sales_order_id) WHERE ((is_backorder = false) AND (upper((status)::text) = ANY (ARRAY['PENDING'::text, 'RESERVED'::text, 'PENDING_PRODUCTION'::text, 'PENDING_STOCK'::text])));

-- FK CONSTRAINT: credit_limit_override_requests credit_limit_override_requests_company_id_fkey
ALTER TABLE ONLY public.credit_limit_override_requests
    ADD CONSTRAINT credit_limit_override_requests_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: credit_limit_override_requests credit_limit_override_requests_dealer_id_fkey
ALTER TABLE ONLY public.credit_limit_override_requests
    ADD CONSTRAINT credit_limit_override_requests_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE SET NULL;

-- FK CONSTRAINT: credit_limit_override_requests credit_limit_override_requests_packaging_slip_id_fkey
ALTER TABLE ONLY public.credit_limit_override_requests
    ADD CONSTRAINT credit_limit_override_requests_packaging_slip_id_fkey FOREIGN KEY (packaging_slip_id) REFERENCES public.packaging_slips(id) ON DELETE SET NULL;

-- FK CONSTRAINT: credit_limit_override_requests credit_limit_override_requests_sales_order_id_fkey
ALTER TABLE ONLY public.credit_limit_override_requests
    ADD CONSTRAINT credit_limit_override_requests_sales_order_id_fkey FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id) ON DELETE SET NULL;

-- FK CONSTRAINT: credit_requests credit_requests_company_id_fkey
ALTER TABLE ONLY public.credit_requests
    ADD CONSTRAINT credit_requests_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: credit_requests credit_requests_dealer_id_fkey
ALTER TABLE ONLY public.credit_requests
    ADD CONSTRAINT credit_requests_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE SET NULL;

-- FK CONSTRAINT: dealer_ledger_entries dealer_ledger_entries_dealer_id_fkey
ALTER TABLE ONLY public.dealer_ledger_entries
    ADD CONSTRAINT dealer_ledger_entries_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE CASCADE;

-- FK CONSTRAINT: dealers dealers_company_id_fkey
ALTER TABLE ONLY public.dealers
    ADD CONSTRAINT dealers_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: dealers dealers_portal_user_id_fkey
ALTER TABLE ONLY public.dealers
    ADD CONSTRAINT dealers_portal_user_id_fkey FOREIGN KEY (portal_user_id) REFERENCES public.app_users(id) ON DELETE SET NULL;

-- FK CONSTRAINT: dealers dealers_receivable_account_id_fkey
ALTER TABLE ONLY public.dealers
    ADD CONSTRAINT dealers_receivable_account_id_fkey FOREIGN KEY (receivable_account_id) REFERENCES public.accounts(id) ON DELETE SET NULL;

-- FK CONSTRAINT: invoice_payment_refs fk_invoice_payment_refs_invoice
ALTER TABLE ONLY public.invoice_payment_refs
    ADD CONSTRAINT fk_invoice_payment_refs_invoice FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE CASCADE;

-- FK CONSTRAINT: invoices fk_invoices_journal_entry
ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT fk_invoices_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: packaging_slips fk_packaging_slips_invoice
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT fk_packaging_slips_invoice FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE SET NULL;

-- FK CONSTRAINT: invoice_lines invoice_lines_invoice_id_fkey
ALTER TABLE ONLY public.invoice_lines
    ADD CONSTRAINT invoice_lines_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE CASCADE;

-- FK CONSTRAINT: invoice_sequences invoice_sequences_company_id_fkey
ALTER TABLE ONLY public.invoice_sequences
    ADD CONSTRAINT invoice_sequences_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: invoices invoices_company_id_fkey
ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: invoices invoices_dealer_id_fkey
ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE CASCADE;

-- FK CONSTRAINT: invoices invoices_sales_order_id_fkey
ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_sales_order_id_fkey FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id) ON DELETE SET NULL;

-- FK CONSTRAINT: journal_entries journal_entries_dealer_id_fkey
ALTER TABLE ONLY public.journal_entries
    ADD CONSTRAINT journal_entries_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE SET NULL;

-- FK CONSTRAINT: order_sequences order_sequences_company_id_fkey
ALTER TABLE ONLY public.order_sequences
    ADD CONSTRAINT order_sequences_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packaging_slip_lines packaging_slip_lines_packaging_slip_id_fkey
ALTER TABLE ONLY public.packaging_slip_lines
    ADD CONSTRAINT packaging_slip_lines_packaging_slip_id_fkey FOREIGN KEY (packaging_slip_id) REFERENCES public.packaging_slips(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packaging_slips packaging_slips_cogs_journal_entry_id_fkey
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT packaging_slips_cogs_journal_entry_id_fkey FOREIGN KEY (cogs_journal_entry_id) REFERENCES public.journal_entries(id);

-- FK CONSTRAINT: packaging_slips packaging_slips_company_id_fkey
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT packaging_slips_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packaging_slips packaging_slips_journal_entry_id_fkey
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT packaging_slips_journal_entry_id_fkey FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id);

-- FK CONSTRAINT: packaging_slips packaging_slips_sales_order_id_fkey
ALTER TABLE ONLY public.packaging_slips
    ADD CONSTRAINT packaging_slips_sales_order_id_fkey FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id) ON DELETE CASCADE;

-- FK CONSTRAINT: partner_settlement_allocations partner_settlement_allocations_dealer_id_fkey
ALTER TABLE ONLY public.partner_settlement_allocations
    ADD CONSTRAINT partner_settlement_allocations_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE CASCADE;

-- FK CONSTRAINT: partner_settlement_allocations partner_settlement_allocations_invoice_id_fkey
ALTER TABLE ONLY public.partner_settlement_allocations
    ADD CONSTRAINT partner_settlement_allocations_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE SET NULL;

-- FK CONSTRAINT: promotions promotions_company_id_fkey
ALTER TABLE ONLY public.promotions
    ADD CONSTRAINT promotions_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: sales_order_items sales_order_items_sales_order_id_fkey
ALTER TABLE ONLY public.sales_order_items
    ADD CONSTRAINT sales_order_items_sales_order_id_fkey FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id) ON DELETE CASCADE;

-- FK CONSTRAINT: sales_orders sales_orders_company_id_fkey
ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT sales_orders_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: sales_orders sales_orders_dealer_id_fkey
ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT sales_orders_dealer_id_fkey FOREIGN KEY (dealer_id) REFERENCES public.dealers(id) ON DELETE SET NULL;

-- FK CONSTRAINT: sales_targets sales_targets_company_id_fkey
ALTER TABLE ONLY public.sales_targets
    ADD CONSTRAINT sales_targets_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;
