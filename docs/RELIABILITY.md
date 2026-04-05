# orchestrator-erp — Reliability

Last reviewed: 2026-04-02

This document captures the reliability posture of the orchestrator-erp backend: idempotency patterns, retry/dead-letter handling, outbox guarantees, event propagation semantics, and known safety gaps.

---

## 1) Idempotency Patterns

### 1.1 Write-path idempotency

Most transactional write endpoints use a combination of:

- **Idempotency-Key header** — client-provided unique key that the server uses to detect duplicate submissions.
- **Payload hash/signature** — server-side hash of the request body to detect payload drift on replay.
- **Replay resolution** — when a duplicate key is detected, the server compares the stored payload signature against the incoming request and either returns the stored result (same payload) or rejects the request (different payload).

**Modules using this pattern:** sales order creation, GRN creation, payroll run creation, opening stock import, opening balance import.

### 1.2 Shared idempotency infrastructure

Shared helpers for idempotency key normalization and signature comparison live in the core infrastructure package. Individual modules layer their own replay logic and domain-specific signature checks on top of the shared infrastructure.

### 1.3 Module-local idempotency

Some modules maintain their own idempotency fields and constraints rather than relying solely on the shared infrastructure:

- `SalesCoreEngine` — order idempotency key + payload signature.
- `GoodsReceiptService` — GRN idempotency key + payload hash with lock-based race reconciliation.
- `PayrollRunService` — period+type keyed with signature checks.
- Opening stock/balance imports — import-reference-based replay protection.

## 2) Retry and Dead-Letter Handling

### 2.1 Orchestrator outbox

The orchestrator publishes commands through an outbox pattern:

- Commands are persisted to outbox tables before dispatch.
- Correlation fields (`trace_id`, `idempotency_key`, `request_id`) are persisted for diagnostics and replay safety.
- Failed commands remain in the outbox for manual or scheduled retry.

### 2.2 Spring event bridges

Internal Spring events propagate cross-module side effects:

- `InventoryMovementEvent` → `InventoryAccountingEventListener` for accounting integration.
- `FactorySlipEventListener` for factory slip lifecycle visibility.
- Event listeners execute within the originating transaction boundary by default; `@TransactionalEventListener` is used where after-commit semantics are required.

### 2.3 Dead-letter and failure routing

- GitHub issue submission for support tickets degrades gracefully when integration is disabled.
- Scheduled status sync handles transient failures by polling on a fixed interval (every 5 minutes for GitHub issue status).

### 2.4 Known gaps

- Not all cross-module event paths have explicit dead-letter handling; some failures may require manual intervention.
- Event listener error semantics vary by module — some fail silently, some propagate exceptions.

## 3) Concurrency and Locking

### 3.1 Optimistic and pessimistic locking

Critical entities use a combination of:

- Optimistic locking via JPA version fields.
- Pessimistic locking via `lockBy...` repository calls around critical mutations (e.g., batch stock deductions, GRN processing).
- `DataIntegrityViolationException` handling with duplicate-insert race reconciliation.

### 3.2 Atomic stock deductions

Batch-level stock operations use `deductQuantityIfSufficient` flows that atomically check and decrement stock to prevent overselling:

- `FinishedGoodBatch` — dispatch and reservation.
- `RawMaterialBatch` — GRN receipt, production issue, and purchase returns.

## 4) Data Integrity Guarantees

### 4.1 Journal posting integrity

- All journal entries go through centralized `AccountingFacade` / `AccountingCoreEngineCore` paths.
- Journal references are explicit per source module (e.g., `PAYROLL-<runToken>`, `PERIOD-CLOSE-<year><month>`).
- Period controls enforce posted-truth boundaries: corrections use explicit notes/reversals rather than mutation of posted entries.

### 4.2 Tenant isolation

- `CompanyContextFilter` enforces JWT company claim presence and header/claim consistency.
- Lifecycle-aware admission: `SUSPENDED` tenants are read-only, `DEACTIVATED` tenants are denied entirely.
- Super-admin control-plane endpoints have explicit exceptions for lifecycle/runtime policy operations.

## 5) Configuration-Guarded Safety

Some safety properties depend on configuration posture rather than hard architectural prevention:

- `erp.inventory.accounting.events.enabled` — controls whether inventory→accounting event integration is active.
- `erp.github.enabled` — controls support ticket GitHub sync.
- `erp.inventory.opening-stock.enabled` — guards prod opening-stock imports.
- Module gating — optional modules can be enabled/disabled per tenant.

When these toggles are misconfigured, the system may silently skip expected side effects rather than failing closed.

## 6) Migration Safety

### 6.1 Hard-cut migrations

Many v2 migrations are forward-only:

- Auth-v2 scoped accounts (V168 and V169).
- Superadmin control plane (`V167`).
- Opening stock batch-key alignment (`V166`).
- HR/Payroll module pause (`V165`).
- Password reset delivery tracking (`V162`).

These migrations intentionally do not support ad-hoc SQL rollback. Recovery requires snapshot/PITR restore.

### 6.2 Migration runbook requirements

Every `migration_v2` change must update both `docs/runbooks/migrations.md` and `docs/runbooks/rollback.md` with forward plan, dry-run commands, and rollback strategy.

## 7) Known Reliability Gaps

- **Mixed idempotency coverage:** not all write paths have the same idempotency rigor.
- **Event listener error handling:** varies by module; not all paths are fail-closed.
- **Configuration-dependent safety:** some important guarantees rely on correct runtime configuration rather than hard enforcement.
- **Dual migration tracks:** require discipline to ensure parity for features touching schema-critical paths.

## Cross-references

- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — full architecture reference
- [docs/SECURITY.md](SECURITY.md) — security review policy
- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [docs/runbooks/rollback.md](runbooks/rollback.md) — rollback runbook
- [docs/runbooks/migrations.md](runbooks/migrations.md) — migration runbook
