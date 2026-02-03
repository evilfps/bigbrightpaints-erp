# Orchestrator “Strong Arm” Contract (CODE-RED)

Last updated: 2026-02-03

Goal: keep the orchestrator as a marketable differentiator (automation + observability) **without** turning it into a
parallel truth source that can corrupt inventory/accounting.

This is a CODE-RED hardening spec: preserve current behavior where safe, but **fail-closed** on any bypass of canonical
workflows.

References
- Program plan: `docs/CODE-RED/plan-v2.md`
- P0 blockers: `docs/CODE-RED/P0_DEPLOY_BLOCKERS.md`
- State machines: `erp-domain/docs/*STATE_MACHINES.md`
- Orchestrator code: `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/*`

---

## 1) What “Strong Arm” Means (Non‑Negotiable)

The orchestrator is allowed to:
- Accept **commands** (approve, cancel, start workflows).
- Enqueue **events** (outbox) and record an auditable **trace**.
- Call **canonical services** in the ERP domain to do the actual business mutation.
- Provide **health/observability** endpoints for ops.

The orchestrator is NOT allowed to:
- Directly set “final truth” statuses (`SHIPPED/DISPATCHED/PAID/POSTED`) unless the canonical module has already
  created the required chain (slip → invoice → journals → ledger, etc.).
- Create journals or mutate inventory by bypassing `AccountingFacade`/canonical policies.
- Become a second implementation of sales/production/accounting logic.

Invariants (must always hold)
- **Dispatch truth is financial truth**: shipped quantities + links are created only via `POST /api/v1/sales/dispatch/confirm`.
- **Company isolation**: orchestrator must not read/write other-tenant rows (no “read by id then check company”).
- **Idempotency at the boundary**: retries must not duplicate slips/movements/journals.
- **Prod gating is defense-in-depth**: feature flags must be enforced in both controller and service layers (fail-closed).

---

## 2) Command Endpoints: What Is Allowed in CODE-RED

### Orders

Allowed:
- `/api/v1/orchestrator/orders/{orderId}/approve`
  - Allowed side effects: reserve inventory, emit outbox event, write trace audit.

Allowed (restricted):
- `/api/v1/orchestrator/orders/{orderId}/fulfillment`
  - Allowed statuses: `PROCESSING`, `CANCELLED`, `READY_TO_SHIP`
  - Terminal status requests (`SHIPPED`, `DISPATCHED`, `FULFILLED`, `COMPLETED`) are **read-only**:
    - If dispatch truth exists, return the current order status (no mutation).
    - If dispatch truth does **not** exist, fail-closed with a business invalid-state error + a canonicalPath hint.

### Factory dispatch / payroll run (CODE-RED default: disabled)

These remain **feature-flagged OFF** until they route exclusively through canonical module services and are proven
idempotent under retries.

- `/api/v1/orchestrator/factory/dispatch/*` (flag: `orchestrator.factory-dispatch.enabled=false`)
- `/api/v1/orchestrator/payroll/run` (flag: `orchestrator.payroll.enabled=false`)
- `/api/v1/orchestrator/dispatch*` is **hard deprecated** (always returns 410 + canonicalPath; no feature flag).

CODE-RED requirement (do not skip):
- Feature flags must be enforced in the service layer too (`CommandDispatcher` / `IntegrationCoordinator`), not only in
  controllers. This prevents accidental internal callers from bypassing prod gating.
- If factory dispatch/payroll remain disabled, attempts should still create a trace/audit record showing a denied action
  (who/when/company/command) without performing side effects.

---

## 3) Security & Tenant Isolation Requirements

Tenant isolation rules:
- Company context MUST come from authenticated JWT claims.
- If `X-Company-Id` is present, it must match the JWT `cid` claim (otherwise 403).
- Anonymous requests must not be able to set company context via headers.

Repo/query rules (security posture):
- Replace `findById(...)` + “check company” with `findByCompanyAndId(...)`/scoped queries to avoid cross-tenant existence probes.

Surface area:
- Swagger/OpenAPI and actuator endpoints must be treated as public attack surface unless explicitly placed on a secured
  management port in prod.

---

## 4) Eventing & Audit: Minimum Enterprise Guarantees

Minimum guarantees for marketing-safe “enterprise automation”:
- Every orchestrator command produces:
  1) a **traceId**
  2) an **audit record** (who, what, company, when, outcome)
  3) an **outbox event** (for async/automation consumers)
- Consumers MUST be idempotent under at-least-once delivery (outbox retry/replay).

What exists now (CODE-RED baseline)
- Orchestrator write commands require `Idempotency-Key` and reserve `(company, commandName, idempotencyKey)` in `orchestrator_commands`.
- Outbox rows include `company_id` for tenant scoping and now persist `traceId/requestId/idempotencyKey` for queryable postmortems.
- Audit records store `traceId` plus `requestId/idempotencyKey` as first-class columns (no JSON parsing required).
- Trace/audit details are stored as JSON (not `Map.toString()`), and trace writes require a valid company.
 - Status-bypass attempts to set terminal fulfillment statuses are fail-closed unless dispatch truth exists.
 - Orchestrator fulfillment updates route through SalesService workflow guards (no direct status setters).

Target event envelope (future hardening; some fields now exist):
- `commandId`
- `idempotencyKey` + `requestId` + `traceId` (caller-provided or server-derived)
- `companyId`
- `userId`
- `entity` + `entityId`
- `eventType`
- `timestamp`
- `payload`

CODE-RED note:
- If we cannot prove consumer idempotency, the corresponding orchestrator command endpoint stays prod-gated OFF.

---

## 5) CODE-RED Test Matrix (Must Exist Before Enabling Orchestrator Writes)

P0 tests (must pass before enabling orchestrator command flags):
- Orchestrator cannot set `SHIPPED/DISPATCHED` via fulfillment update.
 - Orchestrator fulfillment updates allow non-terminal transitions (`PROCESSING`, `READY_TO_SHIP`) only when dispatch truth is absent.
 - Terminal fulfillment requests are read-only when dispatch truth exists.
- `X-Company-Id` mismatch is rejected (403).
- Orchestrator health endpoints require admin/ops auth.
- Orchestrator idempotency: same `Idempotency-Key` replays return the same `traceId` and do not enqueue duplicate outbox events.
- Feature flags are enforced in the service layer (flag-off must fail-closed even if invoked internally).
  - Tests: `CommandDispatcherTest.dispatchBatchFailsClosedWhenFactoryDispatchDisabled`,
    `CommandDispatcherTest.runPayrollFailsClosedWhenPayrollDisabled`,
    `IntegrationCoordinatorTest.updateProductionStatusFailsClosedWhenFactoryDispatchDisabled`,
    `IntegrationCoordinatorTest.generatePayrollFailsClosedWhenPayrollDisabled`,
    `IntegrationCoordinatorTest.recordPayrollPaymentFailsClosedWhenPayrollDisabled`.
- Dispatch journal replay safety: same reference with a mismatched amount/account set must fail closed (409/conflict),
  not silently return an incompatible existing journal.

P1 tests (add next):
- Outbox retry does not duplicate business side effects (idempotent consumers).
- Multi-instance safety: scheduled publishing uses deterministic locking (ShedLock or equivalent).

---

## 6) Rollout Rules (Safe Adoption Path)

Default stance: orchestrator write endpoints remain OFF in prod.

Enablement checklist per command (must be satisfied):
1) Canonical routing proven (no direct status setters / no direct posting).
2) Idempotency keys documented and enforced.
3) DB uniqueness guards exist where needed.
4) Tests exist for retry + concurrency.
5) GO/NO-GO checklist explicitly lists the flag and its safety requirements.
