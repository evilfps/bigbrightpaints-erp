-- V93: Performance indexes for accounting hot paths

CREATE INDEX IF NOT EXISTS idx_journal_company_dealer_date
    ON journal_entries (company_id, dealer_id, entry_date DESC);

CREATE INDEX IF NOT EXISTS idx_dealer_ledger_company_dealer_date
    ON dealer_ledger_entries (company_id, dealer_id, entry_date);

CREATE INDEX IF NOT EXISTS idx_supplier_ledger_company_supplier_date
    ON supplier_ledger_entries (company_id, supplier_id, entry_date);

CREATE INDEX IF NOT EXISTS idx_dealer_ledger_company_status_dealer_due
    ON dealer_ledger_entries (company_id, payment_status, dealer_id, due_date);

CREATE INDEX IF NOT EXISTS idx_acct_events_company_account_effective_ts
    ON accounting_events (company_id, account_id, effective_date, event_timestamp, sequence_number);
