# Task 08 Evidence Run (Idempotency / Retry / Duplication)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/01_idempotency_duplicates.sql
psql -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/02_outbox_backlog_and_duplicates.sql
psql -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/03_partner_settlement_idempotency_index.sql
```

Pass/Fail:
- `01_idempotency_duplicates.sql`: **PASS** if all result sets are empty. **FAIL** if duplicates appear for idempotency keys.
- `02_outbox_backlog_and_duplicates.sql`: **REVIEW** if duplicate rows appear for the same (aggregate_type, aggregate_id, event_type).
- `03_partner_settlement_idempotency_index.sql`: **FAIL** if a unique constraint exists on `(company_id, idempotency_key)` while multi-allocation settlements reuse the same key.

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8080
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/curl/01_outbox_health_gets.sh
```

Pass/Fail:
- Outbox health: record `pendingEvents`, `retryingEvents`, `deadLetters` to correlate with SQL probes. Spikes require investigation.

## Targeted repro (dev-only; optional)
- Submit a dealer or supplier settlement with **multiple allocations** under the same `idempotencyKey`.
- **FAIL** if the request throws a unique index violation on `partner_settlement_allocations`.
