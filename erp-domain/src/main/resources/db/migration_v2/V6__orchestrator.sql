-- Flyway v2: orchestrator/scheduler + deferred cross-module FKs
-- Generated from canonical schema snapshot and grouped by domain phase.

-- TABLE: orchestrator_audit
CREATE TABLE public.orchestrator_audit (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id character varying(128) NOT NULL,
    event_type character varying(128) NOT NULL,
    "timestamp" timestamp with time zone NOT NULL,
    details text NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    company_id bigint,
    request_id character varying(128),
    idempotency_key character varying(255)
);

-- TABLE: orchestrator_commands
CREATE TABLE public.orchestrator_commands (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    command_name character varying(64) NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    request_hash character(64) NOT NULL,
    trace_id character varying(128) NOT NULL,
    status character varying(32) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error text,
    version bigint DEFAULT 0 NOT NULL
);

-- TABLE: orchestrator_outbox
CREATE TABLE public.orchestrator_outbox (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    aggregate_type character varying(128) NOT NULL,
    aggregate_id character varying(128) NOT NULL,
    event_type character varying(128) NOT NULL,
    payload text NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error text,
    dead_letter boolean DEFAULT false NOT NULL,
    company_id bigint,
    trace_id character varying(128),
    request_id character varying(128),
    idempotency_key character varying(255)
);

-- TABLE: order_auto_approval_state
CREATE TABLE public.order_auto_approval_state (
    id bigint NOT NULL,
    company_code character varying(64) NOT NULL,
    order_id bigint NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    inventory_reserved boolean DEFAULT false NOT NULL,
    sales_journal_posted boolean DEFAULT false NOT NULL,
    dispatch_finalized boolean DEFAULT false NOT NULL,
    invoice_issued boolean DEFAULT false NOT NULL,
    order_status_updated boolean DEFAULT false NOT NULL,
    last_error character varying(1024),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: order_auto_approval_state_id_seq
CREATE SEQUENCE public.order_auto_approval_state_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: order_auto_approval_state_id_seq
ALTER SEQUENCE public.order_auto_approval_state_id_seq OWNED BY public.order_auto_approval_state.id;

-- TABLE: scheduled_jobs
CREATE TABLE public.scheduled_jobs (
    job_id character varying(128) NOT NULL,
    cron_expression character varying(64) NOT NULL,
    description character varying(255),
    active boolean DEFAULT true NOT NULL,
    last_run_at timestamp with time zone,
    next_run_at timestamp with time zone,
    owner character varying(128),
    version bigint DEFAULT 0 NOT NULL
);

-- TABLE: shedlock
CREATE TABLE public.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp with time zone NOT NULL,
    locked_at timestamp with time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);

-- DEFAULT: order_auto_approval_state id
ALTER TABLE ONLY public.order_auto_approval_state ALTER COLUMN id SET DEFAULT nextval('public.order_auto_approval_state_id_seq'::regclass);

-- CONSTRAINT: orchestrator_audit orchestrator_audit_pkey
ALTER TABLE ONLY public.orchestrator_audit
    ADD CONSTRAINT orchestrator_audit_pkey PRIMARY KEY (id);

-- CONSTRAINT: orchestrator_commands orchestrator_commands_pkey
ALTER TABLE ONLY public.orchestrator_commands
    ADD CONSTRAINT orchestrator_commands_pkey PRIMARY KEY (id);

-- CONSTRAINT: orchestrator_outbox orchestrator_outbox_pkey
ALTER TABLE ONLY public.orchestrator_outbox
    ADD CONSTRAINT orchestrator_outbox_pkey PRIMARY KEY (id);

-- CONSTRAINT: order_auto_approval_state order_auto_approval_state_pkey
ALTER TABLE ONLY public.order_auto_approval_state
    ADD CONSTRAINT order_auto_approval_state_pkey PRIMARY KEY (id);

-- CONSTRAINT: scheduled_jobs scheduled_jobs_pkey
ALTER TABLE ONLY public.scheduled_jobs
    ADD CONSTRAINT scheduled_jobs_pkey PRIMARY KEY (job_id);

-- CONSTRAINT: shedlock shedlock_pkey
ALTER TABLE ONLY public.shedlock
    ADD CONSTRAINT shedlock_pkey PRIMARY KEY (name);

-- CONSTRAINT: order_auto_approval_state uk_order_auto_approval_company_order
ALTER TABLE ONLY public.order_auto_approval_state
    ADD CONSTRAINT uk_order_auto_approval_company_order UNIQUE (company_code, order_id);

-- INDEX: idx_orchestrator_audit_company_idem
CREATE INDEX idx_orchestrator_audit_company_idem ON public.orchestrator_audit USING btree (company_id, idempotency_key, "timestamp");

-- INDEX: idx_orchestrator_audit_company_request
CREATE INDEX idx_orchestrator_audit_company_request ON public.orchestrator_audit USING btree (company_id, request_id, "timestamp");

-- INDEX: idx_orchestrator_audit_company_trace
CREATE INDEX idx_orchestrator_audit_company_trace ON public.orchestrator_audit USING btree (company_id, trace_id, "timestamp");

-- INDEX: idx_orchestrator_audit_trace
CREATE INDEX idx_orchestrator_audit_trace ON public.orchestrator_audit USING btree (trace_id, "timestamp");

-- INDEX: idx_orchestrator_commands_status
CREATE INDEX idx_orchestrator_commands_status ON public.orchestrator_commands USING btree (company_id, status, updated_at);

-- INDEX: idx_orchestrator_outbox_company_idem
CREATE INDEX idx_orchestrator_outbox_company_idem ON public.orchestrator_outbox USING btree (company_id, idempotency_key);

-- INDEX: idx_orchestrator_outbox_company_request
CREATE INDEX idx_orchestrator_outbox_company_request ON public.orchestrator_outbox USING btree (company_id, request_id);

-- INDEX: idx_orchestrator_outbox_company_status
CREATE INDEX idx_orchestrator_outbox_company_status ON public.orchestrator_outbox USING btree (company_id, status, dead_letter, next_attempt_at);

-- INDEX: idx_orchestrator_outbox_company_trace
CREATE INDEX idx_orchestrator_outbox_company_trace ON public.orchestrator_outbox USING btree (company_id, trace_id);

-- INDEX: idx_orchestrator_outbox_next_attempt
CREATE INDEX idx_orchestrator_outbox_next_attempt ON public.orchestrator_outbox USING btree (status, dead_letter, next_attempt_at);

-- INDEX: idx_orchestrator_outbox_pending_created
CREATE INDEX idx_orchestrator_outbox_pending_created ON public.orchestrator_outbox USING btree (status, dead_letter, next_attempt_at, created_at);

-- INDEX: idx_orchestrator_outbox_status_created
CREATE INDEX idx_orchestrator_outbox_status_created ON public.orchestrator_outbox USING btree (status, created_at);

-- INDEX: ux_orchestrator_commands_scope
CREATE UNIQUE INDEX ux_orchestrator_commands_scope ON public.orchestrator_commands USING btree (company_id, command_name, idempotency_key);

-- FK CONSTRAINT: orchestrator_audit fk_orchestrator_audit_company
ALTER TABLE ONLY public.orchestrator_audit
    ADD CONSTRAINT fk_orchestrator_audit_company FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE SET NULL;

-- FK CONSTRAINT: orchestrator_commands fk_orchestrator_commands_company
ALTER TABLE ONLY public.orchestrator_commands
    ADD CONSTRAINT fk_orchestrator_commands_company FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: orchestrator_outbox fk_orchestrator_outbox_company
ALTER TABLE ONLY public.orchestrator_outbox
    ADD CONSTRAINT fk_orchestrator_outbox_company FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE SET NULL;
