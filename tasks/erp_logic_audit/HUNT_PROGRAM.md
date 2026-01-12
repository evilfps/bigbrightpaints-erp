# Find-the-Flaws Hunt Program

Status: **DRAFT**

Goal: aggressively find ERP-grade correctness flaws (accounting/inventory/workflow/tenancy) that can silently pass tests.

Constraints (this run):
- Discovery + planning only: no behavior changes.
- Prefer read-only evidence first (SQL + GET); use controlled POST probes in dev only when required to prove a flaw.
- Anything suspected-but-unproven goes to `tasks/erp_logic_audit/HUNT_NOTEBOOK.md` as a **LEAD**.

Evidence standard (what counts):
- Code anchor(s): file path + class/method name(s).
- Data proof: read-only SQL output, or API GET output (captured under `tasks/erp_logic_audit/EVIDENCE_QUERIES/` conventions).
- Repro steps: minimal steps that a reviewer can repeat.

Repo anchors for investigations:
- As-built spec: `tasks/erp_logic_audit/AS_BUILT_ERP_SPEC.md`
- Posting/period controls: `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
- Cross-module linkage: `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
- Portal/RBAC matrix: `docs/API_PORTAL_MATRIX.md`

---

## Common “audit oracles” (signals of ERP-wrong state)

These are the core things this hunt program tries to falsify:
- A financially-impacting document exists but has **no journal** (or the journal exists but is unlinked).
- A journal exists but is **unbalanced** (outside tolerance) or is linked to the wrong company/partner.
- Subledgers (dealer/supplier) do not tie to control accounts within tolerance.
- Inventory valuation does not tie to inventory control within tolerance.
- Period controls can be bypassed (posting or modifying into closed/locked periods).
- Idempotency/retry produces duplicates (journals, movements, allocations, invoices).
- Company/tenancy drift: cross-company ID usage accepted by endpoints/repositories.
- “Success” responses that leave inconsistent state (partial commit, silent skip, missing link).

---

## Perspective 1 — Chartered Accountant / Auditor

### Attack vectors (what to try to break)
- Double-entry integrity: unbalanced journals, or “balanced but wrong accounts”.
- Completeness: posted documents missing required journal(s) and/or missing subledger rows.
- Cutoff/timing: journals posted with an entry date in the wrong period; period close can be bypassed.
- AR/AP tie-outs: dealer/supplier ledgers drift from GL control balances; aging doesn’t match ledger.
- GST/VAT: invoice tax vs journal tax vs report tax; rounding differences accumulate; inclusive/exclusive mismatches.
- Inventory valuation/COGS: dispatch posts without COGS; valuation uses different cost layer than postings.

### Expected evidence
- SQL probes showing orphans (document without JE, JE without document), and tie-out variances.
- API report outputs (`/api/v1/reports/*`, `/api/v1/accounting/*`) that disagree with ledger.
- Code anchors showing missing link writes (e.g., not setting `*.journalEntryId`) or conditional skips.

### Minimal probes (read-only)
- SQL:
  - Orphans: movement w/o JE; invoice w/o JE; purchase w/o JE; payroll run posted/paid but journal missing.
  - Tie-outs: AR control vs dealer ledger; AP control vs supplier ledger; inventory control vs valuation.
  - Period integrity: journals dated into closed/locked periods; docs modified after close.
- GET:
  - `/api/v1/reports/trial-balance`
  - `/api/v1/reports/inventory-valuation` and `/api/v1/reports/inventory-reconciliation`
  - `/api/v1/reports/reconciliation-dashboard`
  - `/api/v1/accounting/month-end/checklist`
  - `/api/v1/accounting/gst/return`

### Escalation probes (controlled POST in dev only)
- Attempt dispatch with missing config (accounts) and verify fail-closed behavior (no partial commits).
- Force idempotent replays:
  - repeat settlement/receipt requests with same reference/idempotency key and confirm no duplicates.
  - repeat dispatch confirm and confirm journals/movements are not duplicated.
- Post into locked period with and without admin override and verify enforcement.

---

## Perspective 2 — ERP Operator / User

### Attack vectors
- Dead ends: “cannot finish process” due to missing config surfaced late (accounts, defaults, permissions).
- Misleading entrypoints: endpoints exist but are unreachable by intended portal role; or reachable but break invariants.
- Partial truth states: UI says done but links are missing (e.g., slip dispatched but invoice/journals absent).
- Missing reversal/undo paths: credit note/void/cancel exists in one module but not linked across modules.
- Silent failure paths: action returns success but skips accounting/inventory due to optional mappings.

### Expected evidence
- Portal/role mismatch evidence from `docs/API_PORTAL_MATRIX.md` vs `@PreAuthorize` usage.
- Workflow state machine mismatches: persisted status allows invalid transitions or blocks valid transitions.
- Audit trail gaps: missing `created_by/posted_by`, missing reversal reasons, missing trace IDs.

### Minimal probes (read-only)
- GET endpoint reachability mapping per role:
  - dealer vs admin vs accounting vs factory vs sales.
- State distribution queries:
  - count by status for orders/slips/invoices/purchases/payroll runs, highlighting “stuck” states.
- “Chain-of-evidence” spot checks on recent docs (by date window):
  - order → slip → movements → journals → invoice → dealer ledger.

### Escalation probes (controlled POST in dev only)
- Try the standard “operator recovery” actions:
  - cancel order/backorder, reverse journal, reopen period.
- Confirm that recovery actions unwind links (or are explicitly forbidden) and leave auditable trails.

---

## Perspective 3 — Senior Backend Engineer

### Attack vectors
- Idempotency mismatch:
  - duplicate reference numbers with different payload signature.
  - missing unique constraints on “idempotency keys” where assumed.
- Concurrency:
  - double dispatch, double settlement, double purchase creation under concurrent requests.
  - stock reservation races (reserved > current; negative stock clamps).
- Transaction boundaries:
  - multi-service operations spanning multiple transactions (partial commit risk).
  - alias endpoints calling the same side effects twice.
- Tenancy:
  - endpoints accepting raw IDs that can reference another company’s row.
  - background jobs/outbox handlers missing company context propagation.

### Expected evidence
- Code anchors showing missing `company` predicate in repository methods.
- SQL showing cross-company references (foreign IDs) or duplicate rows by “unique business key”.
- Logs showing optimistic lock retries, duplicate key violations, or partial updates.

### Minimal probes (read-only)
- SQL:
  - duplicates by `(company_id, reference_number)` families (journals, invoices, purchases) and by idempotency key.
  - cross-company foreign key leaks (rows whose linked entity belongs to another company).
  - reservation anomalies: reserved > current, reservations with missing slips, slips with missing reservations.
- GET:
  - list endpoints for journals, orders, purchases, payroll runs; compare counts vs expected links.

### Escalation probes (controlled POST in dev only)
- Concurrency harness (dev only): parallel POSTs with same and different idempotency keys, verify:
  - one journal created, one invoice created, one settlement allocation set created
  - “conflict” returns an error and does not create partial artifacts

---

## Perspective 4 — SRE / Production Readiness

### Attack vectors
- “Health that lies”: service is UP but ERP invariants broken (missing defaults, broken posting paths).
- Drift detection: inconsistencies that only show up via SQL (orphan links, balance drift).
- Backup/restore/audit readiness: restores create reference collisions, sequences out of sync, or missing migration safety.
- Retry policies: at-least-once processing duplicates side effects (outbox, schedulers).

### Expected evidence
- Health endpoints and configuration diagnostics showing missing defaults.
- Repeatable drift queries that can be scheduled as a daily audit job (read-only).
- Restore/snapshot scripts that include sequence + constraint checks.

### Minimal probes (read-only)
- GET:
  - `/actuator/health` (liveness/readiness groups)
  - `/api/integration/health`
  - configuration health endpoints exposed in OpenAPI (e.g., accounting configuration health)
- SQL:
  - reference collisions (journal reference families)
  - sequence vs max(id) checks for key tables
  - “drift dashboard” queries (orphan links, unbalanced entries, negative/over-reserved stock)

### Escalation probes (controlled POST in dev only)
- Chaos-style: deliberately fail mid-flow (simulate exception) and verify rollback prevents partial commits.
- Backup/restore drill: restore into dev and run drift queries + smoke GETs.

