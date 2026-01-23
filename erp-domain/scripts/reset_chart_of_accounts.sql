-- WARNING: destructive reset of chart of accounts and related configuration.
-- This clears account references and deletes all accounts + journal data.
-- Use only in non-production or when data loss is acceptable.

BEGIN;

-- Clear company-level account defaults and GST configuration.
UPDATE companies
SET default_inventory_account_id = NULL,
    default_cogs_account_id = NULL,
    default_revenue_account_id = NULL,
    default_discount_account_id = NULL,
    default_tax_account_id = NULL,
    gst_input_tax_account_id = NULL,
    gst_output_tax_account_id = NULL,
    gst_payable_account_id = NULL,
    payroll_expense_account_id = NULL,
    payroll_cash_account_id = NULL;

-- Clear partner account references.
UPDATE dealers SET receivable_account_id = NULL;
UPDATE suppliers SET payable_account_id = NULL;

-- Clear inventory account references.
UPDATE raw_materials SET inventory_account_id = NULL;
UPDATE finished_goods
SET valuation_account_id = NULL,
    cogs_account_id = NULL,
    revenue_account_id = NULL,
    discount_account_id = NULL,
    tax_account_id = NULL;

-- Remove account metadata from production catalog entries.
UPDATE production_products
SET metadata = metadata
    - 'wipAccountId'
    - 'wastageAccountId'
    - 'semiFinishedAccountId'
    - 'fgValuationAccountId'
    - 'fgCogsAccountId'
    - 'fgRevenueAccountId'
    - 'fgDiscountAccountId'
    - 'fgTaxAccountId'
WHERE metadata IS NOT NULL;

-- Delete journals (cascades to journal_lines).
DELETE FROM journal_entries;

-- Clear accounting event history (optional but avoids orphaned account IDs).
DELETE FROM accounting_events;

-- Delete chart of accounts.
DELETE FROM accounts;

COMMIT;
