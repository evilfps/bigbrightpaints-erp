CREATE TABLE payroll_run_lines (
    id BIGSERIAL PRIMARY KEY,
    payroll_run_id BIGINT NOT NULL REFERENCES payroll_runs(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    days_worked INTEGER NOT NULL,
    daily_wage NUMERIC(18,2) NOT NULL,
    advances NUMERIC(18,2) NOT NULL DEFAULT 0,
    line_total NUMERIC(18,2) NOT NULL,
    notes TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payroll_run_lines_run ON payroll_run_lines(payroll_run_id);
