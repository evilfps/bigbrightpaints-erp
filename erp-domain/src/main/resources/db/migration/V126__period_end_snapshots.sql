CREATE TABLE IF NOT EXISTS accounting_period_snapshots (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    accounting_period_id BIGINT NOT NULL REFERENCES accounting_periods(id),
    as_of_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    trial_balance_total_debit NUMERIC(19,2) NOT NULL DEFAULT 0,
    trial_balance_total_credit NUMERIC(19,2) NOT NULL DEFAULT 0,
    inventory_total_value NUMERIC(19,2) NOT NULL DEFAULT 0,
    inventory_low_stock BIGINT NOT NULL DEFAULT 0,
    ar_subledger_total NUMERIC(19,2) NOT NULL DEFAULT 0,
    ap_subledger_total NUMERIC(19,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_accounting_period_snapshot UNIQUE (company_id, accounting_period_id)
);

CREATE INDEX IF NOT EXISTS idx_accounting_period_snapshots_company
    ON accounting_period_snapshots (company_id);

CREATE INDEX IF NOT EXISTS idx_accounting_period_snapshots_period
    ON accounting_period_snapshots (accounting_period_id);

CREATE TABLE IF NOT EXISTS accounting_period_trial_balance_lines (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    snapshot_id BIGINT NOT NULL REFERENCES accounting_period_snapshots(id) ON DELETE CASCADE,
    account_id BIGINT,
    account_code VARCHAR(50),
    account_name VARCHAR(255),
    account_type VARCHAR(50),
    debit NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit NUMERIC(19,2) NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_accounting_period_trial_balance_snapshot
    ON accounting_period_trial_balance_lines (snapshot_id);

CREATE INDEX IF NOT EXISTS idx_accounting_period_trial_balance_account
    ON accounting_period_trial_balance_lines (account_id);
