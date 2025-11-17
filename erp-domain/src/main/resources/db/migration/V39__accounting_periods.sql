-- Migration: Accounting Periods for Month-End Close
-- Creates accounting_periods table to support period management and month-end close workflows

CREATE TABLE accounting_periods (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    year INTEGER NOT NULL,
    month INTEGER NOT NULL CHECK (month >= 1 AND month <= 12),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    bank_reconciled BOOLEAN NOT NULL DEFAULT FALSE,
    bank_reconciled_at TIMESTAMP,
    bank_reconciled_by VARCHAR(255),
    inventory_counted BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_counted_at TIMESTAMP,
    inventory_counted_by VARCHAR(255),
    checklist_notes TEXT,
    closed_at TIMESTAMP,
    closed_by VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_company_year_month UNIQUE (company_id, year, month)
);

CREATE INDEX idx_accounting_periods_company ON accounting_periods(company_id);
CREATE INDEX idx_accounting_periods_status ON accounting_periods(status);
CREATE INDEX idx_accounting_periods_date_range ON accounting_periods(start_date, end_date);

-- Insert open accounting periods for existing companies (current month and next 2 months)
INSERT INTO accounting_periods (
    public_id,
    company_id,
    year,
    month,
    start_date,
    end_date,
    status,
    version
)
SELECT
    gen_random_uuid(),
    c.id,
    EXTRACT(YEAR FROM d.period_start)::INTEGER,
    EXTRACT(MONTH FROM d.period_start)::INTEGER,
    d.period_start,
    (d.period_start + INTERVAL '1 month - 1 day')::DATE,
    'OPEN',
    0
FROM companies c
CROSS JOIN (
    SELECT DATE_TRUNC('month', CURRENT_DATE) AS period_start
    UNION ALL
    SELECT DATE_TRUNC('month', CURRENT_DATE + INTERVAL '1 month')
    UNION ALL
    SELECT DATE_TRUNC('month', CURRENT_DATE + INTERVAL '2 months')
) d
ON CONFLICT (company_id, year, month) DO NOTHING;
