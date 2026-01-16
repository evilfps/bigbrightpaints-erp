-- Purpose:
--   Demonstrate orchestrator_audit rows are scoped by company_id and queries can filter by company.

WITH trace AS (
    SELECT 'LF-008-' || substring(md5(random()::text) for 6) AS trace_id
),
company_a AS (
    INSERT INTO companies (name, code)
    VALUES ('LF-008 A Ltd', 'LF-008-A')
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
    RETURNING id
),
company_b AS (
    INSERT INTO companies (name, code)
    VALUES ('LF-008 B Ltd', 'LF-008-B')
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
    RETURNING id
),
inserted AS (
    INSERT INTO orchestrator_audit (trace_id, event_type, timestamp, details, company_id)
    SELECT trace.trace_id, 'ORDER_APPROVED', NOW(), 'LF-008 trace', company_a.id
    FROM trace, company_a
    RETURNING id
)
SELECT trace_id,
       company_id,
       event_type,
       timestamp
FROM orchestrator_audit
WHERE trace_id = (SELECT trace_id FROM trace)
ORDER BY timestamp;

SELECT trace_id,
       company_id,
       event_type
FROM orchestrator_audit
WHERE trace_id = (SELECT trace_id FROM trace)
  AND company_id = (SELECT id FROM company_b);
