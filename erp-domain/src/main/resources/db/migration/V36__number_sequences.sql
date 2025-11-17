CREATE TABLE IF NOT EXISTS number_sequences (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    sequence_key VARCHAR(128) NOT NULL,
    next_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_number_sequences_company_key UNIQUE (company_id, sequence_key)
);

CREATE INDEX IF NOT EXISTS idx_number_sequences_company_key
    ON number_sequences (company_id, sequence_key);
