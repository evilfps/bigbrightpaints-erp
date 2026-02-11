-- Flyway v2: core/auth/rbac/system/audit baseline
-- Generated from canonical schema snapshot and grouped by domain phase.

-- EXTENSION: pgcrypto
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- TABLE: app_users
CREATE TABLE public.app_users (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    display_name character varying(255) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    mfa_secret character varying(128),
    mfa_enabled boolean DEFAULT false NOT NULL,
    mfa_recovery_codes text,
    preferred_name character varying(255),
    job_title character varying(255),
    profile_picture_url character varying(512),
    phone_secondary character varying(64),
    secondary_email character varying(255),
    version bigint DEFAULT 0 NOT NULL,
    failed_login_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    reset_token character varying(255),
    reset_expiry timestamp without time zone,
    must_change_password boolean DEFAULT false NOT NULL
);

-- SEQUENCE: app_users_id_seq
CREATE SEQUENCE public.app_users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: app_users_id_seq
ALTER SEQUENCE public.app_users_id_seq OWNED BY public.app_users.id;

-- TABLE: audit_log_metadata
CREATE TABLE public.audit_log_metadata (
    audit_log_id bigint NOT NULL,
    metadata_key character varying(255) NOT NULL,
    metadata_value text
);

-- TABLE: audit_logs
CREATE TABLE public.audit_logs (
    id bigint NOT NULL,
    event_type character varying(100) NOT NULL,
    "timestamp" timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    user_id character varying(255),
    username character varying(255),
    company_id bigint,
    ip_address character varying(45),
    user_agent text,
    request_method character varying(10),
    request_path character varying(500),
    resource_type character varying(100),
    resource_id character varying(255),
    status character varying(20) DEFAULT 'SUCCESS'::character varying,
    error_message character varying(500),
    details text,
    trace_id character varying(100),
    session_id character varying(100),
    duration_ms bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: audit_logs_id_seq
CREATE SEQUENCE public.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: audit_logs_id_seq
ALTER SEQUENCE public.audit_logs_id_seq OWNED BY public.audit_logs.id;

-- TABLE: blacklisted_tokens
CREATE TABLE public.blacklisted_tokens (
    id bigint NOT NULL,
    token_id character varying(255) NOT NULL,
    user_id character varying(255),
    expires_at timestamp without time zone NOT NULL,
    blacklisted_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reason character varying(255)
);

-- SEQUENCE: blacklisted_tokens_id_seq
CREATE SEQUENCE public.blacklisted_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: blacklisted_tokens_id_seq
ALTER SEQUENCE public.blacklisted_tokens_id_seq OWNED BY public.blacklisted_tokens.id;

-- TABLE: companies
CREATE TABLE public.companies (
    id bigint NOT NULL,
    public_id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(255) NOT NULL,
    code character varying(64) NOT NULL,
    timezone character varying(64) DEFAULT 'UTC'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    payroll_expense_account_id bigint,
    payroll_cash_account_id bigint,
    default_gst_rate numeric(9,4) DEFAULT 18 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    base_currency character varying(8) DEFAULT 'INR'::character varying NOT NULL,
    gst_input_tax_account_id bigint,
    gst_output_tax_account_id bigint,
    gst_payable_account_id bigint,
    default_inventory_account_id bigint,
    default_cogs_account_id bigint,
    default_revenue_account_id bigint,
    default_discount_account_id bigint,
    default_tax_account_id bigint
);

-- SEQUENCE: companies_id_seq
CREATE SEQUENCE public.companies_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: companies_id_seq
ALTER SEQUENCE public.companies_id_seq OWNED BY public.companies.id;

-- TABLE: mfa_recovery_codes
CREATE TABLE public.mfa_recovery_codes (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    code_hash character varying(255) NOT NULL,
    used_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: mfa_recovery_codes_id_seq
CREATE SEQUENCE public.mfa_recovery_codes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: mfa_recovery_codes_id_seq
ALTER SEQUENCE public.mfa_recovery_codes_id_seq OWNED BY public.mfa_recovery_codes.id;

-- TABLE: number_sequences
CREATE TABLE public.number_sequences (
    id bigint NOT NULL,
    company_id bigint NOT NULL,
    sequence_key character varying(128) NOT NULL,
    next_value bigint DEFAULT 1 NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: number_sequences_id_seq
CREATE SEQUENCE public.number_sequences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: number_sequences_id_seq
ALTER SEQUENCE public.number_sequences_id_seq OWNED BY public.number_sequences.id;

-- TABLE: password_reset_tokens
CREATE TABLE public.password_reset_tokens (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: password_reset_tokens_id_seq
CREATE SEQUENCE public.password_reset_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: password_reset_tokens_id_seq
ALTER SEQUENCE public.password_reset_tokens_id_seq OWNED BY public.password_reset_tokens.id;

-- TABLE: permissions
CREATE TABLE public.permissions (
    id bigint NOT NULL,
    code character varying(128) NOT NULL,
    description character varying(255),
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: permissions_id_seq
CREATE SEQUENCE public.permissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: permissions_id_seq
ALTER SEQUENCE public.permissions_id_seq OWNED BY public.permissions.id;

-- TABLE: refresh_tokens
CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    token character varying(255) NOT NULL,
    user_email character varying(255) NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

-- SEQUENCE: refresh_tokens_id_seq
CREATE SEQUENCE public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: refresh_tokens_id_seq
ALTER SEQUENCE public.refresh_tokens_id_seq OWNED BY public.refresh_tokens.id;

-- TABLE: role_permissions
CREATE TABLE public.role_permissions (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);

-- TABLE: roles
CREATE TABLE public.roles (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    description character varying(255),
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: roles_id_seq
CREATE SEQUENCE public.roles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: roles_id_seq
ALTER SEQUENCE public.roles_id_seq OWNED BY public.roles.id;

-- TABLE: system_settings
CREATE TABLE public.system_settings (
    setting_key character varying(100) NOT NULL,
    setting_value character varying(2000)
);

-- TABLE: user_companies
CREATE TABLE public.user_companies (
    user_id bigint NOT NULL,
    company_id bigint NOT NULL
);

-- TABLE: user_password_history
CREATE TABLE public.user_password_history (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    password_hash character varying(255) NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

-- SEQUENCE: user_password_history_id_seq
CREATE SEQUENCE public.user_password_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: user_password_history_id_seq
ALTER SEQUENCE public.user_password_history_id_seq OWNED BY public.user_password_history.id;

-- TABLE: user_roles
CREATE TABLE public.user_roles (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL
);

-- TABLE: user_token_revocations
CREATE TABLE public.user_token_revocations (
    id bigint NOT NULL,
    user_id character varying(255) NOT NULL,
    revoked_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reason character varying(255)
);

-- SEQUENCE: user_token_revocations_id_seq
CREATE SEQUENCE public.user_token_revocations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- SEQUENCE OWNED BY: user_token_revocations_id_seq
ALTER SEQUENCE public.user_token_revocations_id_seq OWNED BY public.user_token_revocations.id;

-- DEFAULT: app_users id
ALTER TABLE ONLY public.app_users ALTER COLUMN id SET DEFAULT nextval('public.app_users_id_seq'::regclass);

-- DEFAULT: audit_logs id
ALTER TABLE ONLY public.audit_logs ALTER COLUMN id SET DEFAULT nextval('public.audit_logs_id_seq'::regclass);

-- DEFAULT: blacklisted_tokens id
ALTER TABLE ONLY public.blacklisted_tokens ALTER COLUMN id SET DEFAULT nextval('public.blacklisted_tokens_id_seq'::regclass);

-- DEFAULT: companies id
ALTER TABLE ONLY public.companies ALTER COLUMN id SET DEFAULT nextval('public.companies_id_seq'::regclass);

-- DEFAULT: mfa_recovery_codes id
ALTER TABLE ONLY public.mfa_recovery_codes ALTER COLUMN id SET DEFAULT nextval('public.mfa_recovery_codes_id_seq'::regclass);

-- DEFAULT: number_sequences id
ALTER TABLE ONLY public.number_sequences ALTER COLUMN id SET DEFAULT nextval('public.number_sequences_id_seq'::regclass);

-- DEFAULT: password_reset_tokens id
ALTER TABLE ONLY public.password_reset_tokens ALTER COLUMN id SET DEFAULT nextval('public.password_reset_tokens_id_seq'::regclass);

-- DEFAULT: permissions id
ALTER TABLE ONLY public.permissions ALTER COLUMN id SET DEFAULT nextval('public.permissions_id_seq'::regclass);

-- DEFAULT: refresh_tokens id
ALTER TABLE ONLY public.refresh_tokens ALTER COLUMN id SET DEFAULT nextval('public.refresh_tokens_id_seq'::regclass);

-- DEFAULT: roles id
ALTER TABLE ONLY public.roles ALTER COLUMN id SET DEFAULT nextval('public.roles_id_seq'::regclass);

-- DEFAULT: user_password_history id
ALTER TABLE ONLY public.user_password_history ALTER COLUMN id SET DEFAULT nextval('public.user_password_history_id_seq'::regclass);

-- DEFAULT: user_token_revocations id
ALTER TABLE ONLY public.user_token_revocations ALTER COLUMN id SET DEFAULT nextval('public.user_token_revocations_id_seq'::regclass);

-- CONSTRAINT: app_users app_users_email_key
ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_email_key UNIQUE (email);

-- CONSTRAINT: app_users app_users_pkey
ALTER TABLE ONLY public.app_users
    ADD CONSTRAINT app_users_pkey PRIMARY KEY (id);

-- CONSTRAINT: audit_log_metadata audit_log_metadata_pkey
ALTER TABLE ONLY public.audit_log_metadata
    ADD CONSTRAINT audit_log_metadata_pkey PRIMARY KEY (audit_log_id, metadata_key);

-- CONSTRAINT: audit_logs audit_logs_pkey
ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);

-- CONSTRAINT: blacklisted_tokens blacklisted_tokens_pkey
ALTER TABLE ONLY public.blacklisted_tokens
    ADD CONSTRAINT blacklisted_tokens_pkey PRIMARY KEY (id);

-- CONSTRAINT: blacklisted_tokens blacklisted_tokens_token_id_key
ALTER TABLE ONLY public.blacklisted_tokens
    ADD CONSTRAINT blacklisted_tokens_token_id_key UNIQUE (token_id);

-- CONSTRAINT: companies companies_code_key
ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_code_key UNIQUE (code);

-- CONSTRAINT: companies companies_pkey
ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);

-- CONSTRAINT: mfa_recovery_codes mfa_recovery_codes_pkey
ALTER TABLE ONLY public.mfa_recovery_codes
    ADD CONSTRAINT mfa_recovery_codes_pkey PRIMARY KEY (id);

-- CONSTRAINT: number_sequences number_sequences_pkey
ALTER TABLE ONLY public.number_sequences
    ADD CONSTRAINT number_sequences_pkey PRIMARY KEY (id);

-- CONSTRAINT: password_reset_tokens password_reset_tokens_pkey
ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);

-- CONSTRAINT: password_reset_tokens password_reset_tokens_token_key
ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_token_key UNIQUE (token);

-- CONSTRAINT: permissions permissions_code_key
ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_code_key UNIQUE (code);

-- CONSTRAINT: permissions permissions_pkey
ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);

-- CONSTRAINT: refresh_tokens refresh_tokens_pkey
ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

-- CONSTRAINT: refresh_tokens refresh_tokens_token_key
ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_key UNIQUE (token);

-- CONSTRAINT: role_permissions role_permissions_pkey
ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);

-- CONSTRAINT: roles roles_name_key
ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_name_key UNIQUE (name);

-- CONSTRAINT: roles roles_pkey
ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);

-- CONSTRAINT: system_settings system_settings_pkey
ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (setting_key);

-- CONSTRAINT: number_sequences uq_number_sequences_company_key
ALTER TABLE ONLY public.number_sequences
    ADD CONSTRAINT uq_number_sequences_company_key UNIQUE (company_id, sequence_key);

-- CONSTRAINT: user_companies user_companies_pkey
ALTER TABLE ONLY public.user_companies
    ADD CONSTRAINT user_companies_pkey PRIMARY KEY (user_id, company_id);

-- CONSTRAINT: user_password_history user_password_history_pkey
ALTER TABLE ONLY public.user_password_history
    ADD CONSTRAINT user_password_history_pkey PRIMARY KEY (id);

-- CONSTRAINT: user_roles user_roles_pkey
ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id);

-- CONSTRAINT: user_token_revocations user_token_revocations_pkey
ALTER TABLE ONLY public.user_token_revocations
    ADD CONSTRAINT user_token_revocations_pkey PRIMARY KEY (id);

-- CONSTRAINT: user_token_revocations user_token_revocations_user_id_key
ALTER TABLE ONLY public.user_token_revocations
    ADD CONSTRAINT user_token_revocations_user_id_key UNIQUE (user_id);

-- INDEX: idx_audit_company_id
CREATE INDEX idx_audit_company_id ON public.audit_logs USING btree (company_id);

-- INDEX: idx_audit_event_type
CREATE INDEX idx_audit_event_type ON public.audit_logs USING btree (event_type);

-- INDEX: idx_audit_failed_logins
CREATE INDEX idx_audit_failed_logins ON public.audit_logs USING btree (username, event_type, "timestamp") WHERE ((event_type)::text = 'LOGIN_FAILURE'::text);

-- INDEX: idx_audit_ip_address
CREATE INDEX idx_audit_ip_address ON public.audit_logs USING btree (ip_address);

-- INDEX: idx_audit_security_alerts
CREATE INDEX idx_audit_security_alerts ON public.audit_logs USING btree (event_type, "timestamp") WHERE ((event_type)::text = 'SECURITY_ALERT'::text);

-- INDEX: idx_audit_session_id
CREATE INDEX idx_audit_session_id ON public.audit_logs USING btree (session_id);

-- INDEX: idx_audit_status
CREATE INDEX idx_audit_status ON public.audit_logs USING btree (status);

-- INDEX: idx_audit_timestamp
CREATE INDEX idx_audit_timestamp ON public.audit_logs USING btree ("timestamp");

-- INDEX: idx_audit_trace_id
CREATE INDEX idx_audit_trace_id ON public.audit_logs USING btree (trace_id);

-- INDEX: idx_audit_user_id
CREATE INDEX idx_audit_user_id ON public.audit_logs USING btree (user_id);

-- INDEX: idx_blacklisted_tokens_expires_at
CREATE INDEX idx_blacklisted_tokens_expires_at ON public.blacklisted_tokens USING btree (expires_at);

-- INDEX: idx_blacklisted_tokens_token_id
CREATE INDEX idx_blacklisted_tokens_token_id ON public.blacklisted_tokens USING btree (token_id);

-- INDEX: idx_blacklisted_tokens_user_id
CREATE INDEX idx_blacklisted_tokens_user_id ON public.blacklisted_tokens USING btree (user_id);

-- INDEX: idx_mfa_recovery_codes_used_at
CREATE INDEX idx_mfa_recovery_codes_used_at ON public.mfa_recovery_codes USING btree (used_at) WHERE (used_at IS NOT NULL);

-- INDEX: idx_mfa_recovery_codes_user_id
CREATE INDEX idx_mfa_recovery_codes_user_id ON public.mfa_recovery_codes USING btree (user_id);

-- INDEX: idx_mfa_recovery_user_id_unused
CREATE UNIQUE INDEX idx_mfa_recovery_user_id_unused ON public.mfa_recovery_codes USING btree (user_id, code_hash) WHERE (used_at IS NULL);

-- INDEX: idx_number_sequences_company_key
CREATE INDEX idx_number_sequences_company_key ON public.number_sequences USING btree (company_id, sequence_key);

-- INDEX: idx_password_reset_tokens_user_id
CREATE INDEX idx_password_reset_tokens_user_id ON public.password_reset_tokens USING btree (user_id);

-- INDEX: idx_refresh_tokens_expires_at
CREATE INDEX idx_refresh_tokens_expires_at ON public.refresh_tokens USING btree (expires_at);

-- INDEX: idx_refresh_tokens_user_email
CREATE INDEX idx_refresh_tokens_user_email ON public.refresh_tokens USING btree (user_email);

-- INDEX: idx_user_password_history_user
CREATE INDEX idx_user_password_history_user ON public.user_password_history USING btree (user_id, changed_at DESC);

-- INDEX: idx_user_token_revocations_user_id
CREATE INDEX idx_user_token_revocations_user_id ON public.user_token_revocations USING btree (user_id);

-- FK CONSTRAINT: audit_log_metadata audit_log_metadata_audit_log_id_fkey
ALTER TABLE ONLY public.audit_log_metadata
    ADD CONSTRAINT audit_log_metadata_audit_log_id_fkey FOREIGN KEY (audit_log_id) REFERENCES public.audit_logs(id) ON DELETE CASCADE;

-- FK CONSTRAINT: mfa_recovery_codes mfa_recovery_codes_user_id_fkey
ALTER TABLE ONLY public.mfa_recovery_codes
    ADD CONSTRAINT mfa_recovery_codes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: number_sequences number_sequences_company_id_fkey
ALTER TABLE ONLY public.number_sequences
    ADD CONSTRAINT number_sequences_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: password_reset_tokens password_reset_tokens_user_id_fkey
ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: role_permissions role_permissions_permission_id_fkey
ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;

-- FK CONSTRAINT: role_permissions role_permissions_role_id_fkey
ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_companies user_companies_company_id_fkey
ALTER TABLE ONLY public.user_companies
    ADD CONSTRAINT user_companies_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_companies user_companies_user_id_fkey
ALTER TABLE ONLY public.user_companies
    ADD CONSTRAINT user_companies_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_password_history user_password_history_user_id_fkey
ALTER TABLE ONLY public.user_password_history
    ADD CONSTRAINT user_password_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_roles user_roles_role_id_fkey
ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;

-- FK CONSTRAINT: user_roles user_roles_user_id_fkey
ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT user_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id) ON DELETE CASCADE;
