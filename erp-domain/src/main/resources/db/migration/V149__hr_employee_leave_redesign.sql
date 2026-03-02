-- HR employee and leave redesign for Indian payroll

CREATE TABLE IF NOT EXISTS salary_structure_templates (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    basic_pay DECIMAL(19,2) NOT NULL DEFAULT 0,
    hra DECIMAL(19,2) NOT NULL DEFAULT 0,
    da DECIMAL(19,2) NOT NULL DEFAULT 0,
    special_allowance DECIMAL(19,2) NOT NULL DEFAULT 0,
    employee_pf_rate DECIMAL(5,2) NOT NULL DEFAULT 12.00,
    employee_esi_rate DECIMAL(5,2) NOT NULL DEFAULT 0.75,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_salary_structure_templates_company_code UNIQUE (company_id, code)
);

CREATE INDEX IF NOT EXISTS idx_salary_structure_templates_company_name
    ON salary_structure_templates (company_id, name);

ALTER TABLE employees ADD COLUMN IF NOT EXISTS date_of_birth DATE;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS gender VARCHAR(16);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS emergency_contact_name VARCHAR(128);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS emergency_contact_phone VARCHAR(32);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS department VARCHAR(128);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS designation VARCHAR(128);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS date_of_joining DATE;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS employment_type VARCHAR(32) DEFAULT 'FULL_TIME';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS pf_number VARCHAR(64);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS esi_number VARCHAR(64);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS pan_number VARCHAR(16);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS tax_regime VARCHAR(16) DEFAULT 'NEW';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS salary_structure_template_id BIGINT;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_account_number_encrypted TEXT;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_name_encrypted TEXT;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS ifsc_code_encrypted TEXT;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_branch_encrypted TEXT;

UPDATE employees
SET date_of_joining = hired_date
WHERE date_of_joining IS NULL AND hired_date IS NOT NULL;

UPDATE employees
SET tax_regime = 'NEW'
WHERE tax_regime IS NULL OR BTRIM(tax_regime) = '';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_name = 'employees'
          AND constraint_name = 'fk_employees_salary_structure_template'
    ) THEN
        ALTER TABLE employees
            ADD CONSTRAINT fk_employees_salary_structure_template
                FOREIGN KEY (salary_structure_template_id)
                    REFERENCES salary_structure_templates(id)
                    ON DELETE SET NULL;
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS leave_type_policies (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    leave_type VARCHAR(32) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    annual_entitlement DECIMAL(10,2) NOT NULL,
    carry_forward_limit DECIMAL(10,2) NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_leave_type_policies_company_type UNIQUE (company_id, leave_type)
);

CREATE INDEX IF NOT EXISTS idx_leave_type_policies_company_active
    ON leave_type_policies (company_id, active);

INSERT INTO leave_type_policies (company_id, leave_type, display_name, annual_entitlement, carry_forward_limit, active)
SELECT c.id, policy.leave_type, policy.display_name, policy.annual_entitlement, policy.carry_forward_limit, TRUE
FROM companies c
         CROSS JOIN (
    VALUES ('CASUAL', 'Casual Leave', 12.00::DECIMAL(10,2), 5.00::DECIMAL(10,2)),
           ('SICK', 'Sick Leave', 12.00::DECIMAL(10,2), 0.00::DECIMAL(10,2)),
           ('EARNED', 'Earned Leave', 18.00::DECIMAL(10,2), 10.00::DECIMAL(10,2)),
           ('MATERNITY', 'Maternity Leave', 180.00::DECIMAL(10,2), 0.00::DECIMAL(10,2))
    ) AS policy(leave_type, display_name, annual_entitlement, carry_forward_limit)
ON CONFLICT (company_id, leave_type) DO NOTHING;

CREATE TABLE IF NOT EXISTS leave_balances (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    leave_type VARCHAR(32) NOT NULL,
    balance_year INTEGER NOT NULL,
    opening_balance DECIMAL(10,2) NOT NULL DEFAULT 0,
    accrued DECIMAL(10,2) NOT NULL DEFAULT 0,
    used DECIMAL(10,2) NOT NULL DEFAULT 0,
    remaining DECIMAL(10,2) NOT NULL DEFAULT 0,
    carry_forward_applied DECIMAL(10,2) NOT NULL DEFAULT 0,
    last_recalculated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_leave_balances_employee_year_type UNIQUE (company_id, employee_id, leave_type, balance_year)
);

CREATE INDEX IF NOT EXISTS idx_leave_balances_employee_year
    ON leave_balances (company_id, employee_id, balance_year);

ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS total_days DECIMAL(10,2);
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS decision_reason TEXT;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS approved_by VARCHAR(255);
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS rejected_by VARCHAR(255);
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_leave_requests_company_employee_status
    ON leave_requests (company_id, employee_id, status);

CREATE INDEX IF NOT EXISTS idx_attendance_company_employee_date
    ON attendance (company_id, employee_id, attendance_date);

CREATE INDEX IF NOT EXISTS idx_attendance_company_date_status
    ON attendance (company_id, attendance_date, status);
