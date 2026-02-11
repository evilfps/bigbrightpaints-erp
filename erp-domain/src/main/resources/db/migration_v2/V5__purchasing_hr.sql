-- Flyway v2: purchasing/hr/payroll baseline
-- Generated from canonical schema snapshot and grouped by domain phase.

-- TABLE: attendance
CREATE TABLE public.attendance (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    attendance_date date NOT NULL,
    status character varying(20) DEFAULT 'ABSENT'::character varying NOT NULL,
    check_in_time time without time zone,
    check_out_time time without time zone,
    marked_by character varying(255),
    marked_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    remarks text,
    is_holiday boolean DEFAULT false,
    is_weekend boolean DEFAULT false,
    regular_hours numeric(5,2),
    overtime_hours numeric(5,2),
    double_overtime_hours numeric(5,2),
    base_pay numeric(19,2),
    overtime_pay numeric(19,2),
    total_pay numeric(19,2),
    payroll_run_id bigint
);

-- SEQUENCE: attendance_id_seq
CREATE SEQUENCE public.attendance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: attendance_id_seq
ALTER SEQUENCE public.attendance_id_seq OWNED BY public.attendance.id;

-- TABLE: employees
CREATE TABLE public.employees (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    first_name character varying(128) NOT NULL,
    last_name character varying(128) NOT NULL,
    email character varying(255) NOT NULL,
    role character varying(128),
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    hired_date date,
    version bigint DEFAULT 0 NOT NULL,
    phone character varying(20),
    employee_type character varying(20) DEFAULT 'STAFF'::character varying,
    monthly_salary numeric(19,2),
    daily_wage numeric(19,2),
    payment_schedule character varying(20) DEFAULT 'MONTHLY'::character varying,
    working_days_per_month integer DEFAULT 26,
    weekly_off_days integer DEFAULT 1,
    bank_account_number character varying(50),
    bank_name character varying(100),
    ifsc_code character varying(20),
    advance_balance numeric(19,2) DEFAULT 0,
    overtime_rate_multiplier numeric(5,2) DEFAULT 1.5,
    double_ot_rate_multiplier numeric(5,2) DEFAULT 2.0,
    standard_hours_per_day numeric(5,2) DEFAULT 8
);

-- SEQUENCE: employees_id_seq
CREATE SEQUENCE public.employees_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: employees_id_seq
ALTER SEQUENCE public.employees_id_seq OWNED BY public.employees.id;

-- TABLE: goods_receipt_items
CREATE TABLE public.goods_receipt_items (
    id bigint NOT NULL,
    goods_receipt_id bigint NOT NULL,
    raw_material_id bigint NOT NULL,
    raw_material_batch_id bigint,
    batch_code character varying(128) NOT NULL,
    quantity numeric(18,4) NOT NULL,
    unit character varying(64) NOT NULL,
    cost_per_unit numeric(18,4) NOT NULL,
    line_total numeric(18,4) NOT NULL,
    notes text,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: goods_receipt_items_id_seq
CREATE SEQUENCE public.goods_receipt_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: goods_receipt_items_id_seq
ALTER SEQUENCE public.goods_receipt_items_id_seq OWNED BY public.goods_receipt_items.id;

-- TABLE: goods_receipts
CREATE TABLE public.goods_receipts (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    supplier_id bigint NOT NULL,
    purchase_order_id bigint NOT NULL,
    receipt_number character varying(128) NOT NULL,
    receipt_date date NOT NULL,
    status character varying(32) DEFAULT 'RECEIVED'::character varying NOT NULL,
    memo text,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    idempotency_key character varying(128),
    idempotency_hash character varying(64)
);

-- SEQUENCE: goods_receipts_id_seq
CREATE SEQUENCE public.goods_receipts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: goods_receipts_id_seq
ALTER SEQUENCE public.goods_receipts_id_seq OWNED BY public.goods_receipts.id;

-- TABLE: leave_requests
CREATE TABLE public.leave_requests (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    employee_id bigint,
    leave_type character varying(64) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: leave_requests_id_seq
CREATE SEQUENCE public.leave_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: leave_requests_id_seq
ALTER SEQUENCE public.leave_requests_id_seq OWNED BY public.leave_requests.id;

-- TABLE: payroll_run_lines
CREATE TABLE public.payroll_run_lines (
    id bigint NOT NULL,
    payroll_run_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    days_worked integer NOT NULL,
    daily_wage numeric(18,2) NOT NULL,
    advances numeric(18,2) DEFAULT 0 NOT NULL,
    line_total numeric(18,2) NOT NULL,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    employee_id bigint,
    present_days numeric(5,2) DEFAULT 0,
    half_days numeric(5,2) DEFAULT 0,
    absent_days numeric(5,2) DEFAULT 0,
    leave_days numeric(5,2) DEFAULT 0,
    holiday_days numeric(5,2) DEFAULT 0,
    regular_hours numeric(10,2) DEFAULT 0,
    overtime_hours numeric(10,2) DEFAULT 0,
    double_ot_hours numeric(10,2) DEFAULT 0,
    daily_rate numeric(19,2),
    hourly_rate numeric(19,2),
    ot_rate_multiplier numeric(5,2) DEFAULT 1.5,
    double_ot_multiplier numeric(5,2) DEFAULT 2.0,
    base_pay numeric(19,2) DEFAULT 0,
    overtime_pay numeric(19,2) DEFAULT 0,
    holiday_pay numeric(19,2) DEFAULT 0,
    gross_pay numeric(19,2) DEFAULT 0,
    advance_deduction numeric(19,2) DEFAULT 0,
    pf_deduction numeric(19,2) DEFAULT 0,
    tax_deduction numeric(19,2) DEFAULT 0,
    other_deductions numeric(19,2) DEFAULT 0,
    total_deductions numeric(19,2) DEFAULT 0,
    net_pay numeric(19,2) DEFAULT 0,
    payment_status character varying(20) DEFAULT 'PENDING'::character varying,
    payment_reference character varying(100),
    remarks text
);

-- SEQUENCE: payroll_run_lines_id_seq
CREATE SEQUENCE public.payroll_run_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: payroll_run_lines_id_seq
ALTER SEQUENCE public.payroll_run_lines_id_seq OWNED BY public.payroll_run_lines.id;

-- TABLE: payroll_runs
CREATE TABLE public.payroll_runs (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    run_date date NOT NULL,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    processed_by character varying(255),
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    total_amount numeric(18,2) DEFAULT 0,
    journal_entry_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    idempotency_key character varying(255),
    run_number character varying(50) NOT NULL,
    run_type character varying(20) DEFAULT 'MONTHLY'::character varying NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    total_employees integer DEFAULT 0,
    total_present_days numeric(10,2) DEFAULT 0,
    total_overtime_hours numeric(10,2) DEFAULT 0,
    total_base_pay numeric(19,2) DEFAULT 0,
    total_overtime_pay numeric(19,2) DEFAULT 0,
    total_deductions numeric(19,2) DEFAULT 0,
    total_net_pay numeric(19,2) DEFAULT 0,
    journal_entry_ref_id bigint,
    approved_by character varying(255),
    approved_at timestamp with time zone,
    posted_by character varying(255),
    posted_at timestamp with time zone,
    remarks text,
    created_by character varying(255),
    idempotency_hash character varying(64),
    payment_journal_entry_id bigint
);

-- SEQUENCE: payroll_runs_id_seq
CREATE SEQUENCE public.payroll_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: payroll_runs_id_seq
ALTER SEQUENCE public.payroll_runs_id_seq OWNED BY public.payroll_runs.id;

-- TABLE: purchase_order_items
CREATE TABLE public.purchase_order_items (
    id bigint NOT NULL,
    purchase_order_id bigint NOT NULL,
    raw_material_id bigint NOT NULL,
    quantity numeric(18,4) NOT NULL,
    unit character varying(64) NOT NULL,
    cost_per_unit numeric(18,4) NOT NULL,
    line_total numeric(18,4) NOT NULL,
    notes text,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: purchase_order_items_id_seq
CREATE SEQUENCE public.purchase_order_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: purchase_order_items_id_seq
ALTER SEQUENCE public.purchase_order_items_id_seq OWNED BY public.purchase_order_items.id;

-- TABLE: purchase_orders
CREATE TABLE public.purchase_orders (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    supplier_id bigint NOT NULL,
    order_number character varying(128) NOT NULL,
    order_date date NOT NULL,
    status character varying(32) DEFAULT 'OPEN'::character varying NOT NULL,
    memo text,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- SEQUENCE: purchase_orders_id_seq
CREATE SEQUENCE public.purchase_orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: purchase_orders_id_seq
ALTER SEQUENCE public.purchase_orders_id_seq OWNED BY public.purchase_orders.id;

-- TABLE: raw_material_purchase_items
CREATE TABLE public.raw_material_purchase_items (
    id bigint NOT NULL,
    purchase_id bigint NOT NULL,
    raw_material_id bigint NOT NULL,
    raw_material_batch_id bigint,
    batch_code character varying(128) NOT NULL,
    quantity numeric(18,4) NOT NULL,
    unit character varying(64) NOT NULL,
    cost_per_unit numeric(18,4) NOT NULL,
    line_total numeric(18,4) NOT NULL,
    notes text,
    version bigint DEFAULT 0 NOT NULL,
    tax_rate numeric(10,4),
    tax_amount numeric(18,4),
    returned_quantity numeric(18,4) DEFAULT 0 NOT NULL
);

-- SEQUENCE: raw_material_purchase_items_id_seq
CREATE SEQUENCE public.raw_material_purchase_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: raw_material_purchase_items_id_seq
ALTER SEQUENCE public.raw_material_purchase_items_id_seq OWNED BY public.raw_material_purchase_items.id;

-- TABLE: raw_material_purchases
CREATE TABLE public.raw_material_purchases (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    supplier_id bigint NOT NULL,
    invoice_number character varying(128) NOT NULL,
    invoice_date date NOT NULL,
    total_amount numeric(18,2) DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'POSTED'::character varying NOT NULL,
    memo text,
    journal_entry_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    outstanding_amount numeric(18,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(18,2) DEFAULT 0 NOT NULL,
    purchase_order_id bigint,
    goods_receipt_id bigint
);

-- SEQUENCE: raw_material_purchases_id_seq
CREATE SEQUENCE public.raw_material_purchases_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: raw_material_purchases_id_seq
ALTER SEQUENCE public.raw_material_purchases_id_seq OWNED BY public.raw_material_purchases.id;

-- TABLE: suppliers
CREATE TABLE public.suppliers (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    email character varying(255),
    phone character varying(64),
    address text,
    credit_limit numeric(18,2) DEFAULT 0 NOT NULL,
    outstanding_balance numeric(18,2) DEFAULT 0 NOT NULL,
    payable_account_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: suppliers_id_seq
CREATE SEQUENCE public.suppliers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: suppliers_id_seq
ALTER SEQUENCE public.suppliers_id_seq OWNED BY public.suppliers.id;

-- DEFAULT: attendance id
ALTER TABLE ONLY public.attendance ALTER COLUMN id SET DEFAULT nextval('public.attendance_id_seq'::regclass);

-- DEFAULT: employees id
ALTER TABLE ONLY public.employees ALTER COLUMN id SET DEFAULT nextval('public.employees_id_seq'::regclass);

-- DEFAULT: goods_receipt_items id
ALTER TABLE ONLY public.goods_receipt_items ALTER COLUMN id SET DEFAULT nextval('public.goods_receipt_items_id_seq'::regclass);

-- DEFAULT: goods_receipts id
ALTER TABLE ONLY public.goods_receipts ALTER COLUMN id SET DEFAULT nextval('public.goods_receipts_id_seq'::regclass);

-- DEFAULT: leave_requests id
ALTER TABLE ONLY public.leave_requests ALTER COLUMN id SET DEFAULT nextval('public.leave_requests_id_seq'::regclass);

-- DEFAULT: payroll_run_lines id
ALTER TABLE ONLY public.payroll_run_lines ALTER COLUMN id SET DEFAULT nextval('public.payroll_run_lines_id_seq'::regclass);

-- DEFAULT: payroll_runs id
ALTER TABLE ONLY public.payroll_runs ALTER COLUMN id SET DEFAULT nextval('public.payroll_runs_id_seq'::regclass);

-- DEFAULT: purchase_order_items id
ALTER TABLE ONLY public.purchase_order_items ALTER COLUMN id SET DEFAULT nextval('public.purchase_order_items_id_seq'::regclass);

-- DEFAULT: purchase_orders id
ALTER TABLE ONLY public.purchase_orders ALTER COLUMN id SET DEFAULT nextval('public.purchase_orders_id_seq'::regclass);

-- DEFAULT: raw_material_purchase_items id
ALTER TABLE ONLY public.raw_material_purchase_items ALTER COLUMN id SET DEFAULT nextval('public.raw_material_purchase_items_id_seq'::regclass);

-- DEFAULT: raw_material_purchases id
ALTER TABLE ONLY public.raw_material_purchases ALTER COLUMN id SET DEFAULT nextval('public.raw_material_purchases_id_seq'::regclass);

-- DEFAULT: suppliers id
ALTER TABLE ONLY public.suppliers ALTER COLUMN id SET DEFAULT nextval('public.suppliers_id_seq'::regclass);

-- CONSTRAINT: attendance attendance_pkey
ALTER TABLE ONLY public.attendance
    ADD CONSTRAINT attendance_pkey PRIMARY KEY (id);

-- CONSTRAINT: employees employees_company_id_email_key
ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_company_id_email_key UNIQUE (company_id, email);

-- CONSTRAINT: employees employees_pkey
ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_pkey PRIMARY KEY (id);

-- CONSTRAINT: goods_receipt_items goods_receipt_items_pkey
ALTER TABLE ONLY public.goods_receipt_items
    ADD CONSTRAINT goods_receipt_items_pkey PRIMARY KEY (id);

-- CONSTRAINT: goods_receipts goods_receipts_company_id_receipt_number_key
ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT goods_receipts_company_id_receipt_number_key UNIQUE (company_id, receipt_number);

-- CONSTRAINT: goods_receipts goods_receipts_pkey
ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT goods_receipts_pkey PRIMARY KEY (id);

-- CONSTRAINT: leave_requests leave_requests_pkey
ALTER TABLE ONLY public.leave_requests
    ADD CONSTRAINT leave_requests_pkey PRIMARY KEY (id);

-- CONSTRAINT: payroll_run_lines payroll_run_lines_pkey
ALTER TABLE ONLY public.payroll_run_lines
    ADD CONSTRAINT payroll_run_lines_pkey PRIMARY KEY (id);

-- CONSTRAINT: payroll_runs payroll_runs_pkey
ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT payroll_runs_pkey PRIMARY KEY (id);

-- CONSTRAINT: purchase_order_items purchase_order_items_pkey
ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_pkey PRIMARY KEY (id);

-- CONSTRAINT: purchase_orders purchase_orders_company_id_order_number_key
ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_company_id_order_number_key UNIQUE (company_id, order_number);

-- CONSTRAINT: purchase_orders purchase_orders_pkey
ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_pkey PRIMARY KEY (id);

-- CONSTRAINT: raw_material_purchase_items raw_material_purchase_items_pkey
ALTER TABLE ONLY public.raw_material_purchase_items
    ADD CONSTRAINT raw_material_purchase_items_pkey PRIMARY KEY (id);

-- CONSTRAINT: raw_material_purchases raw_material_purchases_company_id_invoice_number_key
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_company_id_invoice_number_key UNIQUE (company_id, invoice_number);

-- CONSTRAINT: raw_material_purchases raw_material_purchases_pkey
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_pkey PRIMARY KEY (id);

-- CONSTRAINT: suppliers suppliers_company_id_code_key
ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_company_id_code_key UNIQUE (company_id, code);

-- CONSTRAINT: suppliers suppliers_pkey
ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);

-- CONSTRAINT: attendance uk_attendance_employee_date
ALTER TABLE ONLY public.attendance
    ADD CONSTRAINT uk_attendance_employee_date UNIQUE (company_id, employee_id, attendance_date);

-- INDEX: idx_attendance_date
CREATE INDEX idx_attendance_date ON public.attendance USING btree (company_id, attendance_date);

-- INDEX: idx_attendance_employee
CREATE INDEX idx_attendance_employee ON public.attendance USING btree (employee_id, attendance_date);

-- INDEX: idx_goods_receipt_items_receipt
CREATE INDEX idx_goods_receipt_items_receipt ON public.goods_receipt_items USING btree (goods_receipt_id);

-- INDEX: idx_goods_receipts_company
CREATE INDEX idx_goods_receipts_company ON public.goods_receipts USING btree (company_id);

-- INDEX: idx_goods_receipts_purchase_order
CREATE INDEX idx_goods_receipts_purchase_order ON public.goods_receipts USING btree (purchase_order_id);

-- INDEX: idx_payroll_line_employee
CREATE INDEX idx_payroll_line_employee ON public.payroll_run_lines USING btree (employee_id);

-- INDEX: idx_payroll_line_run
CREATE INDEX idx_payroll_line_run ON public.payroll_run_lines USING btree (payroll_run_id);

-- INDEX: idx_payroll_run_lines_run
CREATE INDEX idx_payroll_run_lines_run ON public.payroll_run_lines USING btree (payroll_run_id);

-- INDEX: idx_payroll_run_period
CREATE INDEX idx_payroll_run_period ON public.payroll_runs USING btree (company_id, period_start, period_end);

-- INDEX: idx_payroll_run_status
CREATE INDEX idx_payroll_run_status ON public.payroll_runs USING btree (company_id, status);

-- INDEX: idx_payroll_runs_company_created_at
CREATE INDEX idx_payroll_runs_company_created_at ON public.payroll_runs USING btree (company_id, created_at DESC);

-- INDEX: idx_payroll_runs_company_idempotency_key
CREATE INDEX idx_payroll_runs_company_idempotency_key ON public.payroll_runs USING btree (company_id, idempotency_key);

-- INDEX: idx_payroll_runs_company_type_created_at
CREATE INDEX idx_payroll_runs_company_type_created_at ON public.payroll_runs USING btree (company_id, run_type, created_at DESC);

-- INDEX: idx_payroll_runs_payment_journal
CREATE INDEX idx_payroll_runs_payment_journal ON public.payroll_runs USING btree (company_id, payment_journal_entry_id);

-- INDEX: idx_purchase_order_items_order
CREATE INDEX idx_purchase_order_items_order ON public.purchase_order_items USING btree (purchase_order_id);

-- INDEX: idx_purchase_orders_company
CREATE INDEX idx_purchase_orders_company ON public.purchase_orders USING btree (company_id);

-- INDEX: idx_raw_material_purchase_items_purchase
CREATE INDEX idx_raw_material_purchase_items_purchase ON public.raw_material_purchase_items USING btree (purchase_id);

-- INDEX: idx_raw_material_purchases_company_date
CREATE INDEX idx_raw_material_purchases_company_date ON public.raw_material_purchases USING btree (company_id, invoice_date DESC);

-- INDEX: idx_raw_material_purchases_goods_receipt
CREATE INDEX idx_raw_material_purchases_goods_receipt ON public.raw_material_purchases USING btree (goods_receipt_id);

-- INDEX: idx_raw_material_purchases_purchase_order
CREATE INDEX idx_raw_material_purchases_purchase_order ON public.raw_material_purchases USING btree (purchase_order_id);

-- INDEX: idx_suppliers_company
CREATE INDEX idx_suppliers_company ON public.suppliers USING btree (company_id);

-- INDEX: idx_suppliers_company_name
CREATE INDEX idx_suppliers_company_name ON public.suppliers USING btree (company_id, name);

-- INDEX: uk_payroll_runs_company_idempotency
CREATE UNIQUE INDEX uk_payroll_runs_company_idempotency ON public.payroll_runs USING btree (company_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);

-- INDEX: uq_raw_material_purchases_goods_receipt
CREATE UNIQUE INDEX uq_raw_material_purchases_goods_receipt ON public.raw_material_purchases USING btree (goods_receipt_id);

-- INDEX: ux_goods_receipts_company_idempotency_key
CREATE UNIQUE INDEX ux_goods_receipts_company_idempotency_key ON public.goods_receipts USING btree (company_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);

-- INDEX: ux_payroll_runs_company_period
CREATE UNIQUE INDEX ux_payroll_runs_company_period ON public.payroll_runs USING btree (company_id, run_type, period_start, period_end);

-- FK CONSTRAINT: attendance attendance_company_id_fkey
ALTER TABLE ONLY public.attendance
    ADD CONSTRAINT attendance_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);

-- FK CONSTRAINT: attendance attendance_employee_id_fkey
ALTER TABLE ONLY public.attendance
    ADD CONSTRAINT attendance_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);

-- FK CONSTRAINT: employees employees_company_id_fkey
ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: attendance fk_attendance_payroll_run
ALTER TABLE ONLY public.attendance
    ADD CONSTRAINT fk_attendance_payroll_run FOREIGN KEY (payroll_run_id) REFERENCES public.payroll_runs(id);

-- FK CONSTRAINT: payroll_runs fk_payroll_runs_journal_entry
ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT fk_payroll_runs_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: payroll_runs fk_payroll_runs_payment_journal_entry
ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT fk_payroll_runs_payment_journal_entry FOREIGN KEY (payment_journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: goods_receipt_items goods_receipt_items_goods_receipt_id_fkey
ALTER TABLE ONLY public.goods_receipt_items
    ADD CONSTRAINT goods_receipt_items_goods_receipt_id_fkey FOREIGN KEY (goods_receipt_id) REFERENCES public.goods_receipts(id) ON DELETE CASCADE;

-- FK CONSTRAINT: goods_receipt_items goods_receipt_items_raw_material_batch_id_fkey
ALTER TABLE ONLY public.goods_receipt_items
    ADD CONSTRAINT goods_receipt_items_raw_material_batch_id_fkey FOREIGN KEY (raw_material_batch_id) REFERENCES public.raw_material_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: goods_receipt_items goods_receipt_items_raw_material_id_fkey
ALTER TABLE ONLY public.goods_receipt_items
    ADD CONSTRAINT goods_receipt_items_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: goods_receipts goods_receipts_company_id_fkey
ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT goods_receipts_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: goods_receipts goods_receipts_purchase_order_id_fkey
ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT goods_receipts_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: goods_receipts goods_receipts_supplier_id_fkey
ALTER TABLE ONLY public.goods_receipts
    ADD CONSTRAINT goods_receipts_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: journal_entries journal_entries_supplier_id_fkey
ALTER TABLE ONLY public.journal_entries
    ADD CONSTRAINT journal_entries_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE SET NULL;

-- FK CONSTRAINT: leave_requests leave_requests_company_id_fkey
ALTER TABLE ONLY public.leave_requests
    ADD CONSTRAINT leave_requests_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: leave_requests leave_requests_employee_id_fkey
ALTER TABLE ONLY public.leave_requests
    ADD CONSTRAINT leave_requests_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id) ON DELETE SET NULL;

-- FK CONSTRAINT: partner_settlement_allocations partner_settlement_allocations_purchase_id_fkey
ALTER TABLE ONLY public.partner_settlement_allocations
    ADD CONSTRAINT partner_settlement_allocations_purchase_id_fkey FOREIGN KEY (purchase_id) REFERENCES public.raw_material_purchases(id) ON DELETE SET NULL;

-- FK CONSTRAINT: partner_settlement_allocations partner_settlement_allocations_supplier_id_fkey
ALTER TABLE ONLY public.partner_settlement_allocations
    ADD CONSTRAINT partner_settlement_allocations_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE CASCADE;

-- FK CONSTRAINT: payroll_run_lines payroll_run_lines_payroll_run_id_fkey
ALTER TABLE ONLY public.payroll_run_lines
    ADD CONSTRAINT payroll_run_lines_payroll_run_id_fkey FOREIGN KEY (payroll_run_id) REFERENCES public.payroll_runs(id) ON DELETE CASCADE;

-- FK CONSTRAINT: payroll_runs payroll_runs_company_id_fkey
ALTER TABLE ONLY public.payroll_runs
    ADD CONSTRAINT payroll_runs_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: purchase_order_items purchase_order_items_purchase_order_id_fkey
ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON DELETE CASCADE;

-- FK CONSTRAINT: purchase_order_items purchase_order_items_raw_material_id_fkey
ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: purchase_orders purchase_orders_company_id_fkey
ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: purchase_orders purchase_orders_supplier_id_fkey
ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: raw_material_batches raw_material_batches_supplier_id_fkey
ALTER TABLE ONLY public.raw_material_batches
    ADD CONSTRAINT raw_material_batches_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_purchase_items raw_material_purchase_items_purchase_id_fkey
ALTER TABLE ONLY public.raw_material_purchase_items
    ADD CONSTRAINT raw_material_purchase_items_purchase_id_fkey FOREIGN KEY (purchase_id) REFERENCES public.raw_material_purchases(id) ON DELETE CASCADE;

-- FK CONSTRAINT: raw_material_purchase_items raw_material_purchase_items_raw_material_batch_id_fkey
ALTER TABLE ONLY public.raw_material_purchase_items
    ADD CONSTRAINT raw_material_purchase_items_raw_material_batch_id_fkey FOREIGN KEY (raw_material_batch_id) REFERENCES public.raw_material_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_purchase_items raw_material_purchase_items_raw_material_id_fkey
ALTER TABLE ONLY public.raw_material_purchase_items
    ADD CONSTRAINT raw_material_purchase_items_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: raw_material_purchases raw_material_purchases_company_id_fkey
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: raw_material_purchases raw_material_purchases_goods_receipt_id_fkey
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_goods_receipt_id_fkey FOREIGN KEY (goods_receipt_id) REFERENCES public.goods_receipts(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_purchases raw_material_purchases_journal_entry_id_fkey
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_journal_entry_id_fkey FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_purchases raw_material_purchases_purchase_order_id_fkey
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_purchases raw_material_purchases_supplier_id_fkey
ALTER TABLE ONLY public.raw_material_purchases
    ADD CONSTRAINT raw_material_purchases_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE RESTRICT;

-- FK CONSTRAINT: supplier_ledger_entries supplier_ledger_entries_supplier_id_fkey
ALTER TABLE ONLY public.supplier_ledger_entries
    ADD CONSTRAINT supplier_ledger_entries_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id) ON DELETE CASCADE;

-- FK CONSTRAINT: suppliers suppliers_company_id_fkey
ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: suppliers suppliers_payable_account_id_fkey
ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_payable_account_id_fkey FOREIGN KEY (payable_account_id) REFERENCES public.accounts(id) ON DELETE SET NULL;
