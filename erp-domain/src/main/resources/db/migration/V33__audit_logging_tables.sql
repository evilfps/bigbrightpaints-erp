-- Create comprehensive audit logging tables

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id VARCHAR(255),
    username VARCHAR(255),
    company_id BIGINT,
    ip_address VARCHAR(45),
    user_agent TEXT,
    request_method VARCHAR(10),
    request_path VARCHAR(500),
    resource_type VARCHAR(100),
    resource_id VARCHAR(255),
    status VARCHAR(20) DEFAULT 'SUCCESS',
    error_message VARCHAR(500),
    details TEXT,
    trace_id VARCHAR(100),
    session_id VARCHAR(100),
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create metadata table for additional audit information
CREATE TABLE IF NOT EXISTS audit_log_metadata (
    audit_log_id BIGINT NOT NULL REFERENCES audit_logs(id) ON DELETE CASCADE,
    metadata_key VARCHAR(255) NOT NULL,
    metadata_value TEXT,
    PRIMARY KEY (audit_log_id, metadata_key)
);

-- Create indexes for efficient querying
CREATE INDEX idx_audit_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_company_id ON audit_logs(company_id);
CREATE INDEX idx_audit_ip_address ON audit_logs(ip_address);
CREATE INDEX idx_audit_status ON audit_logs(status);
CREATE INDEX idx_audit_trace_id ON audit_logs(trace_id);
CREATE INDEX idx_audit_session_id ON audit_logs(session_id);

-- Index for finding recent failed login attempts
CREATE INDEX idx_audit_failed_logins ON audit_logs(username, event_type, timestamp)
WHERE event_type = 'LOGIN_FAILURE';

-- Index for security alerts
CREATE INDEX idx_audit_security_alerts ON audit_logs(event_type, timestamp)
WHERE event_type = 'SECURITY_ALERT';

-- Comments for documentation
COMMENT ON TABLE audit_logs IS 'Comprehensive audit logging for security and compliance';
COMMENT ON COLUMN audit_logs.event_type IS 'Type of audited event (LOGIN_SUCCESS, DATA_ACCESS, etc.)';
COMMENT ON COLUMN audit_logs.status IS 'Status of the event (SUCCESS, FAILURE, WARNING, INFO)';
COMMENT ON COLUMN audit_logs.trace_id IS 'Unique identifier for tracing related events';
COMMENT ON COLUMN audit_logs.duration_ms IS 'Duration of the operation in milliseconds';

-- Create a function to automatically clean old audit logs
CREATE OR REPLACE FUNCTION cleanup_old_audit_logs()
RETURNS void AS $$
BEGIN
    -- Delete audit logs older than 1 year (configurable)
    DELETE FROM audit_logs
    WHERE timestamp < NOW() - INTERVAL '1 year';
END;
$$ LANGUAGE plpgsql;

-- Create a scheduled job to run cleanup (requires pg_cron extension)
-- Note: Uncomment if pg_cron is available
-- SELECT cron.schedule('cleanup-audit-logs', '0 2 * * *', 'SELECT cleanup_old_audit_logs();');