-- Flyway v2: inventory/production/factory baseline
-- Generated from canonical schema snapshot and grouped by domain phase.

-- TABLE: catalog_imports
CREATE TABLE public.catalog_imports (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    idempotency_hash character varying(64),
    file_hash character varying(64),
    file_name character varying(256),
    rows_processed integer DEFAULT 0 NOT NULL,
    brands_created integer DEFAULT 0 NOT NULL,
    products_created integer DEFAULT 0 NOT NULL,
    products_updated integer DEFAULT 0 NOT NULL,
    raw_materials_seeded integer DEFAULT 0 NOT NULL,
    errors_json text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: catalog_imports_id_seq
CREATE SEQUENCE public.catalog_imports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: catalog_imports_id_seq
ALTER SEQUENCE public.catalog_imports_id_seq OWNED BY public.catalog_imports.id;

-- TABLE: factory_tasks
CREATE TABLE public.factory_tasks (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    title character varying(255) NOT NULL,
    description text,
    assignee character varying(255),
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    due_date date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    sales_order_id bigint,
    packaging_slip_id bigint
);

-- SEQUENCE: factory_tasks_id_seq
CREATE SEQUENCE public.factory_tasks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: factory_tasks_id_seq
ALTER SEQUENCE public.factory_tasks_id_seq OWNED BY public.factory_tasks.id;

-- TABLE: finished_good_batches
CREATE TABLE public.finished_good_batches (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    finished_good_id bigint NOT NULL,
    batch_code character varying(128) NOT NULL,
    quantity_total numeric(18,3) NOT NULL,
    quantity_available numeric(18,3) NOT NULL,
    unit_cost numeric(18,4) DEFAULT 0 NOT NULL,
    manufactured_at timestamp with time zone DEFAULT now() NOT NULL,
    expiry_date date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    inventory_type character varying(20) DEFAULT 'STANDARD'::character varying NOT NULL,
    parent_batch_id bigint,
    is_bulk boolean DEFAULT false,
    size_label character varying(50),
    CONSTRAINT chk_fg_batch_quantity_available_non_negative CHECK ((quantity_available >= (0)::numeric)),
    CONSTRAINT chk_fg_batch_quantity_total_non_negative CHECK ((quantity_total >= (0)::numeric))
);

-- SEQUENCE: finished_good_batches_id_seq
CREATE SEQUENCE public.finished_good_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: finished_good_batches_id_seq
ALTER SEQUENCE public.finished_good_batches_id_seq OWNED BY public.finished_good_batches.id;

-- TABLE: finished_goods
CREATE TABLE public.finished_goods (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    product_code character varying(128) NOT NULL,
    name character varying(255) NOT NULL,
    unit character varying(32) DEFAULT 'UNIT'::character varying NOT NULL,
    current_stock numeric(18,3) DEFAULT 0 NOT NULL,
    reserved_stock numeric(18,3) DEFAULT 0 NOT NULL,
    costing_method character varying(32) DEFAULT 'FIFO'::character varying NOT NULL,
    valuation_account_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    cogs_account_id bigint,
    revenue_account_id bigint,
    discount_account_id bigint,
    tax_account_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    inventory_type character varying(20) DEFAULT 'STANDARD'::character varying NOT NULL,
    CONSTRAINT chk_finished_goods_current_stock_non_negative CHECK ((current_stock >= (0)::numeric)),
    CONSTRAINT chk_finished_goods_reserved_stock_non_negative CHECK ((reserved_stock >= (0)::numeric))
);

-- SEQUENCE: finished_goods_id_seq
CREATE SEQUENCE public.finished_goods_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: finished_goods_id_seq
ALTER SEQUENCE public.finished_goods_id_seq OWNED BY public.finished_goods.id;

-- TABLE: inventory_adjustment_lines
CREATE TABLE public.inventory_adjustment_lines (
    id bigint NOT NULL,
    adjustment_id bigint NOT NULL,
    finished_good_id bigint NOT NULL,
    quantity numeric(19,4) DEFAULT 0 NOT NULL,
    unit_cost numeric(19,4) DEFAULT 0 NOT NULL,
    amount numeric(19,4) DEFAULT 0 NOT NULL,
    note text,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: inventory_adjustment_lines_id_seq
CREATE SEQUENCE public.inventory_adjustment_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: inventory_adjustment_lines_id_seq
ALTER SEQUENCE public.inventory_adjustment_lines_id_seq OWNED BY public.inventory_adjustment_lines.id;

-- TABLE: inventory_adjustments
CREATE TABLE public.inventory_adjustments (
    id bigint NOT NULL,
    public_id uuid NOT NULL,
    company_id bigint NOT NULL,
    reference_number character varying(255) NOT NULL,
    adjustment_date date NOT NULL,
    adjustment_type character varying(50) NOT NULL,
    reason text,
    status character varying(50) DEFAULT 'DRAFT'::character varying NOT NULL,
    journal_entry_id bigint,
    total_amount numeric(19,4) DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by character varying(255),
    version bigint DEFAULT 0 NOT NULL,
    idempotency_key character varying(128),
    idempotency_hash character varying(64)
);

-- SEQUENCE: inventory_adjustments_id_seq
CREATE SEQUENCE public.inventory_adjustments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: inventory_adjustments_id_seq
ALTER SEQUENCE public.inventory_adjustments_id_seq OWNED BY public.inventory_adjustments.id;

-- TABLE: inventory_movements
CREATE TABLE public.inventory_movements (
    id bigint NOT NULL,
    finished_good_id bigint NOT NULL,
    finished_good_batch_id bigint,
    reference_type character varying(64) NOT NULL,
    reference_id character varying(128) NOT NULL,
    movement_type character varying(32) NOT NULL,
    quantity numeric(18,3) NOT NULL,
    unit_cost numeric(18,4) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    journal_entry_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    packing_slip_id bigint
);

-- SEQUENCE: inventory_movements_id_seq
CREATE SEQUENCE public.inventory_movements_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: inventory_movements_id_seq
ALTER SEQUENCE public.inventory_movements_id_seq OWNED BY public.inventory_movements.id;

-- TABLE: inventory_reservations
CREATE TABLE public.inventory_reservations (
    id bigint NOT NULL,
    raw_material_id bigint,
    reference_type character varying(64) NOT NULL,
    reference_id character varying(128) NOT NULL,
    quantity numeric(18,4) NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_good_id bigint,
    finished_good_batch_id bigint,
    reserved_quantity numeric(18,3),
    fulfilled_quantity numeric(18,3),
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: inventory_reservations_id_seq
CREATE SEQUENCE public.inventory_reservations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: inventory_reservations_id_seq
ALTER SEQUENCE public.inventory_reservations_id_seq OWNED BY public.inventory_reservations.id;

-- TABLE: opening_stock_imports
CREATE TABLE public.opening_stock_imports (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    idempotency_hash character varying(64),
    reference_number character varying(128),
    file_hash character varying(64),
    file_name character varying(256),
    journal_entry_id bigint,
    rows_processed integer DEFAULT 0 NOT NULL,
    raw_materials_created integer DEFAULT 0 NOT NULL,
    raw_material_batches_created integer DEFAULT 0 NOT NULL,
    finished_goods_created integer DEFAULT 0 NOT NULL,
    finished_good_batches_created integer DEFAULT 0 NOT NULL,
    errors_json text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: opening_stock_imports_id_seq
CREATE SEQUENCE public.opening_stock_imports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: opening_stock_imports_id_seq
ALTER SEQUENCE public.opening_stock_imports_id_seq OWNED BY public.opening_stock_imports.id;

-- TABLE: packaging_size_mappings
CREATE TABLE public.packaging_size_mappings (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    packaging_size character varying(50) NOT NULL,
    raw_material_id bigint NOT NULL,
    units_per_pack integer DEFAULT 1 NOT NULL,
    carton_size integer,
    liters_per_unit numeric(19,4) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- SEQUENCE: packaging_size_mappings_id_seq
CREATE SEQUENCE public.packaging_size_mappings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: packaging_size_mappings_id_seq
ALTER SEQUENCE public.packaging_size_mappings_id_seq OWNED BY public.packaging_size_mappings.id;

-- TABLE: packing_records
CREATE TABLE public.packing_records (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    production_log_id bigint NOT NULL,
    finished_good_id bigint NOT NULL,
    finished_good_batch_id bigint,
    packaging_size character varying(128) NOT NULL,
    quantity_packed numeric(18,2) DEFAULT 0 NOT NULL,
    pieces_count integer,
    boxes_count integer,
    pieces_per_box integer,
    packed_date date DEFAULT CURRENT_DATE NOT NULL,
    packed_by character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    packaging_cost numeric(19,4) DEFAULT 0,
    packaging_material_id bigint,
    packaging_quantity numeric(19,4)
);

-- SEQUENCE: packing_records_id_seq
CREATE SEQUENCE public.packing_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: packing_records_id_seq
ALTER SEQUENCE public.packing_records_id_seq OWNED BY public.packing_records.id;

-- TABLE: packing_request_records
CREATE TABLE public.packing_request_records (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    idempotency_hash character varying(64),
    production_log_id bigint NOT NULL,
    packing_record_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: packing_request_records_id_seq
CREATE SEQUENCE public.packing_request_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: packing_request_records_id_seq
ALTER SEQUENCE public.packing_request_records_id_seq OWNED BY public.packing_request_records.id;

-- TABLE: production_batches
CREATE TABLE public.production_batches (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    plan_id bigint,
    batch_number character varying(64) NOT NULL,
    quantity_produced numeric(18,2) NOT NULL,
    produced_at timestamp with time zone DEFAULT now() NOT NULL,
    logged_by character varying(255),
    notes text,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: production_batches_id_seq
CREATE SEQUENCE public.production_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: production_batches_id_seq
ALTER SEQUENCE public.production_batches_id_seq OWNED BY public.production_batches.id;

-- TABLE: production_brands
CREATE TABLE public.production_brands (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    code character varying(64) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: production_brands_id_seq
CREATE SEQUENCE public.production_brands_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: production_brands_id_seq
ALTER SEQUENCE public.production_brands_id_seq OWNED BY public.production_brands.id;

-- TABLE: production_log_materials
CREATE TABLE public.production_log_materials (
    id bigint NOT NULL,
    log_id bigint NOT NULL,
    raw_material_id bigint,
    material_name character varying(255) NOT NULL,
    quantity numeric(18,4) NOT NULL,
    unit_of_measure character varying(64) NOT NULL,
    cost_per_unit numeric(18,4),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    total_cost numeric(18,4) DEFAULT 0 NOT NULL
);

-- SEQUENCE: production_log_materials_id_seq
CREATE SEQUENCE public.production_log_materials_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: production_log_materials_id_seq
ALTER SEQUENCE public.production_log_materials_id_seq OWNED BY public.production_log_materials.id;

-- TABLE: production_logs
CREATE TABLE public.production_logs (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    brand_id bigint NOT NULL,
    product_id bigint NOT NULL,
    production_code character varying(64) NOT NULL,
    batch_colour character varying(128),
    batch_size numeric(18,2) NOT NULL,
    unit_of_measure character varying(64) NOT NULL,
    produced_at timestamp with time zone DEFAULT now() NOT NULL,
    notes text,
    created_by character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    mixed_quantity numeric(18,2) DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'MIXED'::character varying NOT NULL,
    total_packed_quantity numeric(18,2) DEFAULT 0 NOT NULL,
    wastage_quantity numeric(18,2) DEFAULT 0 NOT NULL,
    material_cost_total numeric(18,2) DEFAULT 0 NOT NULL,
    labor_cost_total numeric(18,2) DEFAULT 0 NOT NULL,
    overhead_cost_total numeric(18,2) DEFAULT 0 NOT NULL,
    unit_cost numeric(18,4) DEFAULT 0 NOT NULL,
    sales_order_id bigint,
    sales_order_number character varying(64)
);

-- SEQUENCE: production_logs_id_seq
CREATE SEQUENCE public.production_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: production_logs_id_seq
ALTER SEQUENCE public.production_logs_id_seq OWNED BY public.production_logs.id;

-- TABLE: production_plans
CREATE TABLE public.production_plans (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    plan_number character varying(64) NOT NULL,
    product_name character varying(255) NOT NULL,
    quantity numeric(18,2) NOT NULL,
    planned_date date NOT NULL,
    status character varying(32) DEFAULT 'PLANNED'::character varying NOT NULL,
    notes text,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: production_plans_id_seq
CREATE SEQUENCE public.production_plans_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: production_plans_id_seq
ALTER SEQUENCE public.production_plans_id_seq OWNED BY public.production_plans.id;

-- TABLE: production_products
CREATE TABLE public.production_products (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    brand_id bigint NOT NULL,
    product_name character varying(255) NOT NULL,
    category character varying(64) NOT NULL,
    default_colour character varying(128),
    size_label character varying(64),
    unit_of_measure character varying(64),
    sku_code character varying(128) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    base_price numeric(18,2) DEFAULT 0 NOT NULL,
    gst_rate numeric(5,2) DEFAULT 0 NOT NULL,
    min_discount_percent numeric(5,2) DEFAULT 0 NOT NULL,
    min_selling_price numeric(18,2) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: production_products_id_seq
CREATE SEQUENCE public.production_products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: production_products_id_seq
ALTER SEQUENCE public.production_products_id_seq OWNED BY public.production_products.id;

-- TABLE: raw_material_batches
CREATE TABLE public.raw_material_batches (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_material_id bigint NOT NULL,
    batch_code character varying(128) NOT NULL,
    quantity numeric(18,4) NOT NULL,
    unit character varying(32) NOT NULL,
    cost_per_unit numeric(18,4) NOT NULL,
    supplier character varying(255),
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    notes text,
    supplier_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    inventory_type character varying(20) DEFAULT 'STANDARD'::character varying NOT NULL,
    CONSTRAINT chk_raw_material_batch_quantity_non_negative CHECK ((quantity >= (0)::numeric))
);

-- SEQUENCE: raw_material_batches_id_seq
CREATE SEQUENCE public.raw_material_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: raw_material_batches_id_seq
ALTER SEQUENCE public.raw_material_batches_id_seq OWNED BY public.raw_material_batches.id;

-- TABLE: raw_material_intake_requests
CREATE TABLE public.raw_material_intake_requests (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    idempotency_hash character varying(64),
    raw_material_id bigint,
    raw_material_batch_id bigint,
    raw_material_movement_id bigint,
    journal_entry_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: raw_material_intake_requests_id_seq
CREATE SEQUENCE public.raw_material_intake_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: raw_material_intake_requests_id_seq
ALTER SEQUENCE public.raw_material_intake_requests_id_seq OWNED BY public.raw_material_intake_requests.id;

-- TABLE: raw_material_movements
CREATE TABLE public.raw_material_movements (
    id bigint NOT NULL,
    raw_material_id bigint NOT NULL,
    raw_material_batch_id bigint,
    reference_type character varying(64) NOT NULL,
    reference_id character varying(128) NOT NULL,
    movement_type character varying(32) NOT NULL,
    quantity numeric(18,4) NOT NULL,
    unit_cost numeric(18,4) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    journal_entry_id bigint,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: raw_material_movements_id_seq
CREATE SEQUENCE public.raw_material_movements_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: raw_material_movements_id_seq
ALTER SEQUENCE public.raw_material_movements_id_seq OWNED BY public.raw_material_movements.id;

-- TABLE: raw_materials
CREATE TABLE public.raw_materials (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    sku character varying(128) NOT NULL,
    unit_type character varying(64) NOT NULL,
    reorder_level numeric(18,4) DEFAULT 0 NOT NULL,
    current_stock numeric(18,4) DEFAULT 0 NOT NULL,
    min_stock numeric(18,4) DEFAULT 0 NOT NULL,
    max_stock numeric(18,4) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    inventory_account_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    inventory_type character varying(20) DEFAULT 'STANDARD'::character varying NOT NULL,
    gst_rate numeric(5,2) DEFAULT 0,
    private_stock numeric(19,4) DEFAULT 0 NOT NULL,
    material_type character varying(20) DEFAULT 'PRODUCTION'::character varying NOT NULL,
    costing_method character varying(20) DEFAULT 'FIFO'::character varying NOT NULL,
    CONSTRAINT chk_raw_material_current_stock_non_negative CHECK ((current_stock >= (0)::numeric))
);

-- SEQUENCE: raw_materials_id_seq
CREATE SEQUENCE public.raw_materials_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: raw_materials_id_seq
ALTER SEQUENCE public.raw_materials_id_seq OWNED BY public.raw_materials.id;

-- DEFAULT: catalog_imports id
ALTER TABLE ONLY public.catalog_imports ALTER COLUMN id SET DEFAULT nextval('public.catalog_imports_id_seq'::regclass);

-- DEFAULT: factory_tasks id
ALTER TABLE ONLY public.factory_tasks ALTER COLUMN id SET DEFAULT nextval('public.factory_tasks_id_seq'::regclass);

-- DEFAULT: finished_good_batches id
ALTER TABLE ONLY public.finished_good_batches ALTER COLUMN id SET DEFAULT nextval('public.finished_good_batches_id_seq'::regclass);

-- DEFAULT: finished_goods id
ALTER TABLE ONLY public.finished_goods ALTER COLUMN id SET DEFAULT nextval('public.finished_goods_id_seq'::regclass);

-- DEFAULT: inventory_adjustment_lines id
ALTER TABLE ONLY public.inventory_adjustment_lines ALTER COLUMN id SET DEFAULT nextval('public.inventory_adjustment_lines_id_seq'::regclass);

-- DEFAULT: inventory_adjustments id
ALTER TABLE ONLY public.inventory_adjustments ALTER COLUMN id SET DEFAULT nextval('public.inventory_adjustments_id_seq'::regclass);

-- DEFAULT: inventory_movements id
ALTER TABLE ONLY public.inventory_movements ALTER COLUMN id SET DEFAULT nextval('public.inventory_movements_id_seq'::regclass);

-- DEFAULT: inventory_reservations id
ALTER TABLE ONLY public.inventory_reservations ALTER COLUMN id SET DEFAULT nextval('public.inventory_reservations_id_seq'::regclass);

-- DEFAULT: opening_stock_imports id
ALTER TABLE ONLY public.opening_stock_imports ALTER COLUMN id SET DEFAULT nextval('public.opening_stock_imports_id_seq'::regclass);

-- DEFAULT: packaging_size_mappings id
ALTER TABLE ONLY public.packaging_size_mappings ALTER COLUMN id SET DEFAULT nextval('public.packaging_size_mappings_id_seq'::regclass);

-- DEFAULT: packing_records id
ALTER TABLE ONLY public.packing_records ALTER COLUMN id SET DEFAULT nextval('public.packing_records_id_seq'::regclass);

-- DEFAULT: packing_request_records id
ALTER TABLE ONLY public.packing_request_records ALTER COLUMN id SET DEFAULT nextval('public.packing_request_records_id_seq'::regclass);

-- DEFAULT: production_batches id
ALTER TABLE ONLY public.production_batches ALTER COLUMN id SET DEFAULT nextval('public.production_batches_id_seq'::regclass);

-- DEFAULT: production_brands id
ALTER TABLE ONLY public.production_brands ALTER COLUMN id SET DEFAULT nextval('public.production_brands_id_seq'::regclass);

-- DEFAULT: production_log_materials id
ALTER TABLE ONLY public.production_log_materials ALTER COLUMN id SET DEFAULT nextval('public.production_log_materials_id_seq'::regclass);

-- DEFAULT: production_logs id
ALTER TABLE ONLY public.production_logs ALTER COLUMN id SET DEFAULT nextval('public.production_logs_id_seq'::regclass);

-- DEFAULT: production_plans id
ALTER TABLE ONLY public.production_plans ALTER COLUMN id SET DEFAULT nextval('public.production_plans_id_seq'::regclass);

-- DEFAULT: production_products id
ALTER TABLE ONLY public.production_products ALTER COLUMN id SET DEFAULT nextval('public.production_products_id_seq'::regclass);

-- DEFAULT: raw_material_batches id
ALTER TABLE ONLY public.raw_material_batches ALTER COLUMN id SET DEFAULT nextval('public.raw_material_batches_id_seq'::regclass);

-- DEFAULT: raw_material_intake_requests id
ALTER TABLE ONLY public.raw_material_intake_requests ALTER COLUMN id SET DEFAULT nextval('public.raw_material_intake_requests_id_seq'::regclass);

-- DEFAULT: raw_material_movements id
ALTER TABLE ONLY public.raw_material_movements ALTER COLUMN id SET DEFAULT nextval('public.raw_material_movements_id_seq'::regclass);

-- DEFAULT: raw_materials id
ALTER TABLE ONLY public.raw_materials ALTER COLUMN id SET DEFAULT nextval('public.raw_materials_id_seq'::regclass);

-- CONSTRAINT: catalog_imports catalog_imports_company_id_idempotency_key_key
ALTER TABLE ONLY public.catalog_imports
    ADD CONSTRAINT catalog_imports_company_id_idempotency_key_key UNIQUE (company_id, idempotency_key);

-- CONSTRAINT: catalog_imports catalog_imports_pkey
ALTER TABLE ONLY public.catalog_imports
    ADD CONSTRAINT catalog_imports_pkey PRIMARY KEY (id);

-- CONSTRAINT: factory_tasks factory_tasks_pkey
ALTER TABLE ONLY public.factory_tasks
    ADD CONSTRAINT factory_tasks_pkey PRIMARY KEY (id);

-- CONSTRAINT: finished_good_batches finished_good_batches_finished_good_id_batch_code_key
ALTER TABLE ONLY public.finished_good_batches
    ADD CONSTRAINT finished_good_batches_finished_good_id_batch_code_key UNIQUE (finished_good_id, batch_code);

-- CONSTRAINT: finished_good_batches finished_good_batches_pkey
ALTER TABLE ONLY public.finished_good_batches
    ADD CONSTRAINT finished_good_batches_pkey PRIMARY KEY (id);

-- CONSTRAINT: finished_goods finished_goods_company_id_product_code_key
ALTER TABLE ONLY public.finished_goods
    ADD CONSTRAINT finished_goods_company_id_product_code_key UNIQUE (company_id, product_code);

-- CONSTRAINT: finished_goods finished_goods_pkey
ALTER TABLE ONLY public.finished_goods
    ADD CONSTRAINT finished_goods_pkey PRIMARY KEY (id);

-- CONSTRAINT: inventory_adjustment_lines inventory_adjustment_lines_pkey
ALTER TABLE ONLY public.inventory_adjustment_lines
    ADD CONSTRAINT inventory_adjustment_lines_pkey PRIMARY KEY (id);

-- CONSTRAINT: inventory_adjustments inventory_adjustments_pkey
ALTER TABLE ONLY public.inventory_adjustments
    ADD CONSTRAINT inventory_adjustments_pkey PRIMARY KEY (id);

-- CONSTRAINT: inventory_movements inventory_movements_pkey
ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT inventory_movements_pkey PRIMARY KEY (id);

-- CONSTRAINT: inventory_reservations inventory_reservations_pkey
ALTER TABLE ONLY public.inventory_reservations
    ADD CONSTRAINT inventory_reservations_pkey PRIMARY KEY (id);

-- CONSTRAINT: opening_stock_imports opening_stock_imports_company_id_idempotency_key_key
ALTER TABLE ONLY public.opening_stock_imports
    ADD CONSTRAINT opening_stock_imports_company_id_idempotency_key_key UNIQUE (company_id, idempotency_key);

-- CONSTRAINT: opening_stock_imports opening_stock_imports_pkey
ALTER TABLE ONLY public.opening_stock_imports
    ADD CONSTRAINT opening_stock_imports_pkey PRIMARY KEY (id);

-- CONSTRAINT: packaging_size_mappings packaging_size_mappings_pkey
ALTER TABLE ONLY public.packaging_size_mappings
    ADD CONSTRAINT packaging_size_mappings_pkey PRIMARY KEY (id);

-- CONSTRAINT: packing_records packing_records_pkey
ALTER TABLE ONLY public.packing_records
    ADD CONSTRAINT packing_records_pkey PRIMARY KEY (id);

-- CONSTRAINT: packing_request_records packing_request_records_company_id_idempotency_key_key
ALTER TABLE ONLY public.packing_request_records
    ADD CONSTRAINT packing_request_records_company_id_idempotency_key_key UNIQUE (company_id, idempotency_key);

-- CONSTRAINT: packing_request_records packing_request_records_pkey
ALTER TABLE ONLY public.packing_request_records
    ADD CONSTRAINT packing_request_records_pkey PRIMARY KEY (id);

-- CONSTRAINT: production_batches production_batches_company_id_batch_number_key
ALTER TABLE ONLY public.production_batches
    ADD CONSTRAINT production_batches_company_id_batch_number_key UNIQUE (company_id, batch_number);

-- CONSTRAINT: production_batches production_batches_pkey
ALTER TABLE ONLY public.production_batches
    ADD CONSTRAINT production_batches_pkey PRIMARY KEY (id);

-- CONSTRAINT: production_brands production_brands_pkey
ALTER TABLE ONLY public.production_brands
    ADD CONSTRAINT production_brands_pkey PRIMARY KEY (id);

-- CONSTRAINT: production_log_materials production_log_materials_pkey
ALTER TABLE ONLY public.production_log_materials
    ADD CONSTRAINT production_log_materials_pkey PRIMARY KEY (id);

-- CONSTRAINT: production_logs production_logs_pkey
ALTER TABLE ONLY public.production_logs
    ADD CONSTRAINT production_logs_pkey PRIMARY KEY (id);

-- CONSTRAINT: production_plans production_plans_company_id_plan_number_key
ALTER TABLE ONLY public.production_plans
    ADD CONSTRAINT production_plans_company_id_plan_number_key UNIQUE (company_id, plan_number);

-- CONSTRAINT: production_plans production_plans_pkey
ALTER TABLE ONLY public.production_plans
    ADD CONSTRAINT production_plans_pkey PRIMARY KEY (id);

-- CONSTRAINT: production_products production_products_pkey
ALTER TABLE ONLY public.production_products
    ADD CONSTRAINT production_products_pkey PRIMARY KEY (id);

-- CONSTRAINT: raw_material_batches raw_material_batches_pkey
ALTER TABLE ONLY public.raw_material_batches
    ADD CONSTRAINT raw_material_batches_pkey PRIMARY KEY (id);

-- CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_company_id_idempotency_key_key
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_company_id_idempotency_key_key UNIQUE (company_id, idempotency_key);

-- CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_pkey
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_pkey PRIMARY KEY (id);

-- CONSTRAINT: raw_material_movements raw_material_movements_pkey
ALTER TABLE ONLY public.raw_material_movements
    ADD CONSTRAINT raw_material_movements_pkey PRIMARY KEY (id);

-- CONSTRAINT: raw_materials raw_materials_company_id_sku_key
ALTER TABLE ONLY public.raw_materials
    ADD CONSTRAINT raw_materials_company_id_sku_key UNIQUE (company_id, sku);

-- CONSTRAINT: raw_materials raw_materials_pkey
ALTER TABLE ONLY public.raw_materials
    ADD CONSTRAINT raw_materials_pkey PRIMARY KEY (id);

-- CONSTRAINT: packaging_size_mappings uq_packaging_size_material
ALTER TABLE ONLY public.packaging_size_mappings
    ADD CONSTRAINT uq_packaging_size_material UNIQUE (company_id, packaging_size, raw_material_id);

-- CONSTRAINT: production_brands uq_production_brand_code
ALTER TABLE ONLY public.production_brands
    ADD CONSTRAINT uq_production_brand_code UNIQUE (company_id, code);

-- CONSTRAINT: production_brands uq_production_brand_name
ALTER TABLE ONLY public.production_brands
    ADD CONSTRAINT uq_production_brand_name UNIQUE (company_id, name);

-- CONSTRAINT: production_logs uq_production_log_code
ALTER TABLE ONLY public.production_logs
    ADD CONSTRAINT uq_production_log_code UNIQUE (company_id, production_code);

-- CONSTRAINT: production_products uq_production_product_name
ALTER TABLE ONLY public.production_products
    ADD CONSTRAINT uq_production_product_name UNIQUE (brand_id, product_name);

-- CONSTRAINT: production_products uq_production_product_sku
ALTER TABLE ONLY public.production_products
    ADD CONSTRAINT uq_production_product_sku UNIQUE (company_id, sku_code);

-- CONSTRAINT: raw_material_batches uq_raw_material_batches_code
ALTER TABLE ONLY public.raw_material_batches
    ADD CONSTRAINT uq_raw_material_batches_code UNIQUE (raw_material_id, batch_code);

-- INDEX: idx_catalog_imports_company
CREATE INDEX idx_catalog_imports_company ON public.catalog_imports USING btree (company_id);

-- INDEX: idx_factory_tasks_sales_order_id
CREATE INDEX idx_factory_tasks_sales_order_id ON public.factory_tasks USING btree (sales_order_id);

-- INDEX: idx_fg_batch_bulk
CREATE INDEX idx_fg_batch_bulk ON public.finished_good_batches USING btree (is_bulk) WHERE (is_bulk = true);

-- INDEX: idx_fg_batch_parent
CREATE INDEX idx_fg_batch_parent ON public.finished_good_batches USING btree (parent_batch_id);

-- INDEX: idx_finished_good_batches_qty_available
CREATE INDEX idx_finished_good_batches_qty_available ON public.finished_good_batches USING btree (quantity_available);

-- INDEX: idx_finished_goods_current_stock
CREATE INDEX idx_finished_goods_current_stock ON public.finished_goods USING btree (current_stock);

-- INDEX: idx_finished_goods_reserved_stock
CREATE INDEX idx_finished_goods_reserved_stock ON public.finished_goods USING btree (reserved_stock);

-- INDEX: idx_inventory_adjustment_lines_adjustment
CREATE INDEX idx_inventory_adjustment_lines_adjustment ON public.inventory_adjustment_lines USING btree (adjustment_id);

-- INDEX: idx_inventory_adjustments_company
CREATE INDEX idx_inventory_adjustments_company ON public.inventory_adjustments USING btree (company_id);

-- INDEX: idx_inventory_adjustments_date
CREATE INDEX idx_inventory_adjustments_date ON public.inventory_adjustments USING btree (adjustment_date);

-- INDEX: idx_inventory_adjustments_reference
CREATE INDEX idx_inventory_adjustments_reference ON public.inventory_adjustments USING btree (reference_number);

-- INDEX: idx_inventory_movements_journal_entry
CREATE INDEX idx_inventory_movements_journal_entry ON public.inventory_movements USING btree (journal_entry_id);

-- INDEX: idx_inventory_movements_packing_slip_id
CREATE INDEX idx_inventory_movements_packing_slip_id ON public.inventory_movements USING btree (packing_slip_id);

-- INDEX: idx_inventory_movements_ref
CREATE INDEX idx_inventory_movements_ref ON public.inventory_movements USING btree (reference_type, reference_id);

-- INDEX: idx_inventory_movements_ref_created_at
CREATE INDEX idx_inventory_movements_ref_created_at ON public.inventory_movements USING btree (reference_type, reference_id, created_at);

-- INDEX: idx_inventory_reservations_fg
CREATE INDEX idx_inventory_reservations_fg ON public.inventory_reservations USING btree (finished_good_id);

-- INDEX: idx_inventory_reservations_material
CREATE INDEX idx_inventory_reservations_material ON public.inventory_reservations USING btree (raw_material_id);

-- INDEX: idx_opening_stock_imports_company
CREATE INDEX idx_opening_stock_imports_company ON public.opening_stock_imports USING btree (company_id);

-- INDEX: idx_opening_stock_imports_reference
CREATE INDEX idx_opening_stock_imports_reference ON public.opening_stock_imports USING btree (company_id, reference_number);

-- INDEX: idx_packaging_size_mappings_company
CREATE INDEX idx_packaging_size_mappings_company ON public.packaging_size_mappings USING btree (company_id);

-- INDEX: idx_packaging_size_mappings_company_size
CREATE INDEX idx_packaging_size_mappings_company_size ON public.packaging_size_mappings USING btree (company_id, packaging_size);

-- INDEX: idx_packing_records_company
CREATE INDEX idx_packing_records_company ON public.packing_records USING btree (company_id, packed_date DESC);

-- INDEX: idx_packing_records_log
CREATE INDEX idx_packing_records_log ON public.packing_records USING btree (production_log_id);

-- INDEX: idx_packing_request_records_company_log
CREATE INDEX idx_packing_request_records_company_log ON public.packing_request_records USING btree (company_id, production_log_id);

-- INDEX: idx_production_brands_company
CREATE INDEX idx_production_brands_company ON public.production_brands USING btree (company_id);

-- INDEX: idx_production_log_materials_log
CREATE INDEX idx_production_log_materials_log ON public.production_log_materials USING btree (log_id);

-- INDEX: idx_production_logs_company
CREATE INDEX idx_production_logs_company ON public.production_logs USING btree (company_id, produced_at DESC);

-- INDEX: idx_production_products_brand
CREATE INDEX idx_production_products_brand ON public.production_products USING btree (brand_id);

-- INDEX: idx_production_products_company
CREATE INDEX idx_production_products_company ON public.production_products USING btree (company_id);

-- INDEX: idx_raw_material_batches_material
CREATE INDEX idx_raw_material_batches_material ON public.raw_material_batches USING btree (raw_material_id);

-- INDEX: idx_raw_material_batches_quantity
CREATE INDEX idx_raw_material_batches_quantity ON public.raw_material_batches USING btree (quantity);

-- INDEX: idx_raw_material_intake_requests_company
CREATE INDEX idx_raw_material_intake_requests_company ON public.raw_material_intake_requests USING btree (company_id);

-- INDEX: idx_raw_material_movements_journal_entry
CREATE INDEX idx_raw_material_movements_journal_entry ON public.raw_material_movements USING btree (journal_entry_id);

-- INDEX: idx_raw_material_movements_material
CREATE INDEX idx_raw_material_movements_material ON public.raw_material_movements USING btree (raw_material_id);

-- INDEX: idx_raw_material_type
CREATE INDEX idx_raw_material_type ON public.raw_materials USING btree (company_id, material_type);

-- INDEX: idx_raw_materials_company
CREATE INDEX idx_raw_materials_company ON public.raw_materials USING btree (company_id);

-- INDEX: uq_inventory_adjustments_idempotency
CREATE UNIQUE INDEX uq_inventory_adjustments_idempotency ON public.inventory_adjustments USING btree (company_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);

-- FK CONSTRAINT: catalog_imports catalog_imports_company_id_fkey
ALTER TABLE ONLY public.catalog_imports
    ADD CONSTRAINT catalog_imports_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: factory_tasks factory_tasks_company_id_fkey
ALTER TABLE ONLY public.factory_tasks
    ADD CONSTRAINT factory_tasks_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: finished_good_batches finished_good_batches_finished_good_id_fkey
ALTER TABLE ONLY public.finished_good_batches
    ADD CONSTRAINT finished_good_batches_finished_good_id_fkey FOREIGN KEY (finished_good_id) REFERENCES public.finished_goods(id) ON DELETE CASCADE;

-- FK CONSTRAINT: finished_good_batches finished_good_batches_parent_batch_id_fkey
ALTER TABLE ONLY public.finished_good_batches
    ADD CONSTRAINT finished_good_batches_parent_batch_id_fkey FOREIGN KEY (parent_batch_id) REFERENCES public.finished_good_batches(id);

-- FK CONSTRAINT: finished_goods finished_goods_company_id_fkey
ALTER TABLE ONLY public.finished_goods
    ADD CONSTRAINT finished_goods_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: inventory_movements fk_inventory_movements_journal_entry
ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT fk_inventory_movements_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: inventory_adjustment_lines inventory_adjustment_lines_adjustment_id_fkey
ALTER TABLE ONLY public.inventory_adjustment_lines
    ADD CONSTRAINT inventory_adjustment_lines_adjustment_id_fkey FOREIGN KEY (adjustment_id) REFERENCES public.inventory_adjustments(id) ON DELETE CASCADE;

-- FK CONSTRAINT: inventory_adjustment_lines inventory_adjustment_lines_finished_good_id_fkey
ALTER TABLE ONLY public.inventory_adjustment_lines
    ADD CONSTRAINT inventory_adjustment_lines_finished_good_id_fkey FOREIGN KEY (finished_good_id) REFERENCES public.finished_goods(id);

-- FK CONSTRAINT: inventory_adjustments inventory_adjustments_company_id_fkey
ALTER TABLE ONLY public.inventory_adjustments
    ADD CONSTRAINT inventory_adjustments_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);

-- FK CONSTRAINT: inventory_movements inventory_movements_finished_good_batch_id_fkey
ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT inventory_movements_finished_good_batch_id_fkey FOREIGN KEY (finished_good_batch_id) REFERENCES public.finished_good_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: inventory_movements inventory_movements_finished_good_id_fkey
ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT inventory_movements_finished_good_id_fkey FOREIGN KEY (finished_good_id) REFERENCES public.finished_goods(id) ON DELETE CASCADE;

-- FK CONSTRAINT: inventory_reservations inventory_reservations_finished_good_batch_id_fkey
ALTER TABLE ONLY public.inventory_reservations
    ADD CONSTRAINT inventory_reservations_finished_good_batch_id_fkey FOREIGN KEY (finished_good_batch_id) REFERENCES public.finished_good_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: inventory_reservations inventory_reservations_finished_good_id_fkey
ALTER TABLE ONLY public.inventory_reservations
    ADD CONSTRAINT inventory_reservations_finished_good_id_fkey FOREIGN KEY (finished_good_id) REFERENCES public.finished_goods(id) ON DELETE CASCADE;

-- FK CONSTRAINT: inventory_reservations inventory_reservations_raw_material_id_fkey
ALTER TABLE ONLY public.inventory_reservations
    ADD CONSTRAINT inventory_reservations_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE CASCADE;

-- FK CONSTRAINT: opening_stock_imports opening_stock_imports_company_id_fkey
ALTER TABLE ONLY public.opening_stock_imports
    ADD CONSTRAINT opening_stock_imports_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: opening_stock_imports opening_stock_imports_journal_entry_id_fkey
ALTER TABLE ONLY public.opening_stock_imports
    ADD CONSTRAINT opening_stock_imports_journal_entry_id_fkey FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: packaging_size_mappings packaging_size_mappings_company_id_fkey
ALTER TABLE ONLY public.packaging_size_mappings
    ADD CONSTRAINT packaging_size_mappings_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);

-- FK CONSTRAINT: packaging_size_mappings packaging_size_mappings_raw_material_id_fkey
ALTER TABLE ONLY public.packaging_size_mappings
    ADD CONSTRAINT packaging_size_mappings_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id);

-- FK CONSTRAINT: packaging_slip_lines packaging_slip_lines_finished_good_batch_id_fkey
ALTER TABLE ONLY public.packaging_slip_lines
    ADD CONSTRAINT packaging_slip_lines_finished_good_batch_id_fkey FOREIGN KEY (finished_good_batch_id) REFERENCES public.finished_good_batches(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packing_records packing_records_company_id_fkey
ALTER TABLE ONLY public.packing_records
    ADD CONSTRAINT packing_records_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packing_records packing_records_finished_good_batch_id_fkey
ALTER TABLE ONLY public.packing_records
    ADD CONSTRAINT packing_records_finished_good_batch_id_fkey FOREIGN KEY (finished_good_batch_id) REFERENCES public.finished_good_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: packing_records packing_records_finished_good_id_fkey
ALTER TABLE ONLY public.packing_records
    ADD CONSTRAINT packing_records_finished_good_id_fkey FOREIGN KEY (finished_good_id) REFERENCES public.finished_goods(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packing_records packing_records_packaging_material_id_fkey
ALTER TABLE ONLY public.packing_records
    ADD CONSTRAINT packing_records_packaging_material_id_fkey FOREIGN KEY (packaging_material_id) REFERENCES public.raw_materials(id);

-- FK CONSTRAINT: packing_records packing_records_production_log_id_fkey
ALTER TABLE ONLY public.packing_records
    ADD CONSTRAINT packing_records_production_log_id_fkey FOREIGN KEY (production_log_id) REFERENCES public.production_logs(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packing_request_records packing_request_records_company_id_fkey
ALTER TABLE ONLY public.packing_request_records
    ADD CONSTRAINT packing_request_records_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: packing_request_records packing_request_records_packing_record_id_fkey
ALTER TABLE ONLY public.packing_request_records
    ADD CONSTRAINT packing_request_records_packing_record_id_fkey FOREIGN KEY (packing_record_id) REFERENCES public.packing_records(id) ON DELETE SET NULL;

-- FK CONSTRAINT: packing_request_records packing_request_records_production_log_id_fkey
ALTER TABLE ONLY public.packing_request_records
    ADD CONSTRAINT packing_request_records_production_log_id_fkey FOREIGN KEY (production_log_id) REFERENCES public.production_logs(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_batches production_batches_company_id_fkey
ALTER TABLE ONLY public.production_batches
    ADD CONSTRAINT production_batches_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_batches production_batches_plan_id_fkey
ALTER TABLE ONLY public.production_batches
    ADD CONSTRAINT production_batches_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.production_plans(id) ON DELETE SET NULL;

-- FK CONSTRAINT: production_brands production_brands_company_id_fkey
ALTER TABLE ONLY public.production_brands
    ADD CONSTRAINT production_brands_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_log_materials production_log_materials_log_id_fkey
ALTER TABLE ONLY public.production_log_materials
    ADD CONSTRAINT production_log_materials_log_id_fkey FOREIGN KEY (log_id) REFERENCES public.production_logs(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_log_materials production_log_materials_raw_material_id_fkey
ALTER TABLE ONLY public.production_log_materials
    ADD CONSTRAINT production_log_materials_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE SET NULL;

-- FK CONSTRAINT: production_logs production_logs_brand_id_fkey
ALTER TABLE ONLY public.production_logs
    ADD CONSTRAINT production_logs_brand_id_fkey FOREIGN KEY (brand_id) REFERENCES public.production_brands(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_logs production_logs_company_id_fkey
ALTER TABLE ONLY public.production_logs
    ADD CONSTRAINT production_logs_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_logs production_logs_product_id_fkey
ALTER TABLE ONLY public.production_logs
    ADD CONSTRAINT production_logs_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.production_products(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_plans production_plans_company_id_fkey
ALTER TABLE ONLY public.production_plans
    ADD CONSTRAINT production_plans_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_products production_products_brand_id_fkey
ALTER TABLE ONLY public.production_products
    ADD CONSTRAINT production_products_brand_id_fkey FOREIGN KEY (brand_id) REFERENCES public.production_brands(id) ON DELETE CASCADE;

-- FK CONSTRAINT: production_products production_products_company_id_fkey
ALTER TABLE ONLY public.production_products
    ADD CONSTRAINT production_products_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: raw_material_batches raw_material_batches_raw_material_id_fkey
ALTER TABLE ONLY public.raw_material_batches
    ADD CONSTRAINT raw_material_batches_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE CASCADE;

-- FK CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_company_id_fkey
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_journal_entry_id_fkey
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_journal_entry_id_fkey FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_raw_material_batch_id_fkey
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_raw_material_batch_id_fkey FOREIGN KEY (raw_material_batch_id) REFERENCES public.raw_material_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_raw_material_id_fkey
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_intake_requests raw_material_intake_requests_raw_material_movement_id_fkey
ALTER TABLE ONLY public.raw_material_intake_requests
    ADD CONSTRAINT raw_material_intake_requests_raw_material_movement_id_fkey FOREIGN KEY (raw_material_movement_id) REFERENCES public.raw_material_movements(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_movements raw_material_movements_journal_entry_id_fkey
ALTER TABLE ONLY public.raw_material_movements
    ADD CONSTRAINT raw_material_movements_journal_entry_id_fkey FOREIGN KEY (journal_entry_id) REFERENCES public.journal_entries(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_movements raw_material_movements_raw_material_batch_id_fkey
ALTER TABLE ONLY public.raw_material_movements
    ADD CONSTRAINT raw_material_movements_raw_material_batch_id_fkey FOREIGN KEY (raw_material_batch_id) REFERENCES public.raw_material_batches(id) ON DELETE SET NULL;

-- FK CONSTRAINT: raw_material_movements raw_material_movements_raw_material_id_fkey
ALTER TABLE ONLY public.raw_material_movements
    ADD CONSTRAINT raw_material_movements_raw_material_id_fkey FOREIGN KEY (raw_material_id) REFERENCES public.raw_materials(id) ON DELETE CASCADE;

-- FK CONSTRAINT: raw_materials raw_materials_company_id_fkey
ALTER TABLE ONLY public.raw_materials
    ADD CONSTRAINT raw_materials_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;
