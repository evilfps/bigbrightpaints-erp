-- Event store for accounting domain events
-- Enables full audit trail, temporal queries, and event replay

CREATE TABLE IF NOT EXISTS accounting_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    event_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_date DATE NOT NULL,

    -- Denormalized for efficient queries
    account_id BIGINT,
    account_code VARCHAR(50),
    journal_entry_id BIGINT,
    journal_reference VARCHAR(100),

    -- Amount tracking
    debit_amount NUMERIC(19, 4),
    credit_amount NUMERIC(19, 4),
    balance_before NUMERIC(19, 4),
    balance_after NUMERIC(19, 4),

    -- Audit fields
    description VARCHAR(500),
    user_id VARCHAR(100),
    correlation_id UUID,
    payload TEXT,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);

-- Indexes for common query patterns
CREATE INDEX idx_acct_events_company_ts ON accounting_events(company_id, event_timestamp);
CREATE INDEX idx_acct_events_account ON accounting_events(account_id, event_timestamp);
CREATE INDEX idx_acct_events_account_date ON accounting_events(account_id, effective_date);
CREATE INDEX idx_acct_events_journal ON accounting_events(journal_entry_id);
CREATE INDEX idx_acct_events_aggregate ON accounting_events(aggregate_id, sequence_number);
CREATE INDEX idx_acct_events_correlation ON accounting_events(correlation_id);
CREATE INDEX idx_acct_events_type ON accounting_events(company_id, event_type);

COMMENT ON TABLE accounting_events IS 'Event store for accounting domain - enables temporal queries and full audit trail';
COMMENT ON COLUMN accounting_events.aggregate_id IS 'UUID identifying the aggregate root (JournalEntry or Account)';
COMMENT ON COLUMN accounting_events.sequence_number IS 'Monotonically increasing sequence within an aggregate';
COMMENT ON COLUMN accounting_events.effective_date IS 'Business date for the event (for date-based queries)';
COMMENT ON COLUMN accounting_events.balance_before IS 'Account balance before this event';
COMMENT ON COLUMN accounting_events.balance_after IS 'Account balance after this event (enables point-in-time queries)';
COMMENT ON COLUMN accounting_events.correlation_id IS 'Links all events from the same transaction';
