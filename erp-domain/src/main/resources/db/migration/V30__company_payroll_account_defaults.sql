ALTER TABLE companies
    ADD COLUMN payroll_expense_account_id BIGINT,
    ADD COLUMN payroll_cash_account_id BIGINT;

ALTER TABLE companies
    ADD CONSTRAINT fk_company_payroll_expense_account
        FOREIGN KEY (payroll_expense_account_id)
        REFERENCES accounts (id),
    ADD CONSTRAINT fk_company_payroll_cash_account
        FOREIGN KEY (payroll_cash_account_id)
        REFERENCES accounts (id);
