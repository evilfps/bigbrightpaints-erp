-- Ensure aggregate event sequence numbers are unique for replay ordering
CREATE UNIQUE INDEX IF NOT EXISTS uk_accounting_events_aggregate_sequence
    ON accounting_events(aggregate_id, sequence_number);
