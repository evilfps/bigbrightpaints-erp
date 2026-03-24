-- Create payroll GL accounts for all existing companies
-- These accounts are required for payroll journal posting

-- Insert payroll accounts for each company that doesn't have them
INSERT INTO accounts (public_id, company_id, code, name, type, balance, active, hierarchy_level, version)
SELECT
    gen_random_uuid(),
    c.id,
    acc.code,
    acc.name,
    acc.type,
    0,
    true,
    1,
    0
FROM companies c
CROSS JOIN (VALUES
    ('SALARY-EXP', 'Salary Expense', 'EXPENSE'),
    ('WAGE-EXP', 'Wage Expense', 'EXPENSE'),
    ('PF-PAYABLE', 'Provident Fund Payable', 'LIABILITY'),
    ('SALARY-PAYABLE', 'Salary Payable', 'LIABILITY'),
    ('TAX-PAYABLE', 'Tax Payable', 'LIABILITY')
) AS acc(code, name, type)
WHERE NOT EXISTS (
    SELECT 1 FROM accounts a
    WHERE a.company_id = c.id AND UPPER(a.code) = UPPER(acc.code)
);
