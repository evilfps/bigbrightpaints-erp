# CODE-RED De-duplication Backlog (No Feature Loss)

Last updated: 2026-02-02

Goal: streamline/centralize the system **without breaking functionality** by ensuring there is exactly one canonical write
path per business event, while keeping legacy endpoints as safe aliases or prod-gated repair tooling.

Reference docs:
- Canonical flow map: `erp-domain/docs/MODULE_FLOW_MAP.md`
- Cross-module linkage: `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
- Endpoint list: `erp-domain/docs/endpoint_inventory.tsv`
- Duplicates inventory: `erp-domain/docs/DUPLICATES_REPORT.md`
- CODE-RED detailed plan: `docs/CODE-RED/plan-v2.md` (EPIC 00)

## Implemented (CODE-RED)
- Orchestrator can no longer “fake dispatch truth”:
  - `/api/v1/orchestrator/dispatch*` always returns 410 + `canonicalPath`.
  - Orchestrator fulfillment rejects `SHIPPED/DISPATCHED` and points to canonical dispatch confirm.
- Orchestrator write commands are idempotent at the boundary:
  - `Idempotency-Key` required; scope reserved in `orchestrator_commands` and replays return the same `traceId`.
- Payroll payment is split from payroll posting:
  - `POST /api/v1/accounting/payroll/payments` clears `SALARY-PAYABLE` (not expense) and stores `payroll_runs.payment_journal_entry_id`.
  - `POST /api/v1/payroll/runs/{id}/mark-paid` requires a payment journal to exist (HR updates lines + advances).

## Backlog (Prioritized)

### P0 (Deploy blockers)
- Dispatch confirmation
  - Duplicates: `POST /api/v1/sales/dispatch/confirm`, `POST /api/v1/dispatch/confirm`, orchestrator `/api/v1/orchestrator/dispatch*`
  - Canonical: `SalesService.confirmDispatch(...)` via `POST /api/v1/sales/dispatch/confirm`
  - Action: inventory alias must call canonical; orchestrator dispatch remains prod-gated; add alias-parity tests.
  - Additional P0 guard: forbid any alternate COGS posting path that can double-post with a different reference namespace.
    - Risk: `SalesFulfillmentService` can post COGS by order number, while dispatch confirm posts COGS by slip number.
    - Action: remove/prod-gate non-canonical COGS posting and standardize COGS idempotency reference to per-slip (`COGS-<slipNumber>`).
    - Status (2026-02-03): ✅ order-level COGS posting disabled; slip-scoped COGS linkage enforced; tests: `OrderFulfillmentE2ETest.dispatchCogs_matchesSlipUnitCosts_andLinksMovements`, `OrderFulfillmentE2ETest.partialDispatch_invoicesShippedQty_andCreatesBackorderSlip`, `SalesFulfillmentServiceTest.forcesOrderLevelCogsPostingDisabled`.
  - Additional P0 guard: forbid AR/Revenue double-posting when a canonical dispatch sales journal already exists.
    - Risk: dispatch confirm may provide `invoiceNumber` and unintentionally create a second AR/Revenue journal even if the canonical dispatch
      reference already exists (`INV-<orderNumber>` for single-slip, `INV-<orderNumber>-<slipNumber>` for multi-slip).
    - Action: sales journal dedupe must check both the requested reference and the canonical dispatch reference and then ensure
      `journal_reference_mappings` link the canonical reference to the actual stored reference.
    - Status (2026-02-03): ✅ sales journals canonicalize with invoice-number alias mapping + mismatch-safe idempotency; tests: `CriticalAccountingAxesIT` sales journal idempotency coverage.
  - Additional P0 guard: idempotency must be mismatch-safe (no silent divergence).
    - Rule: if a replay hits an existing reference but payload differs materially (amount/accounts), fail closed with a conflict instead of returning
      an incompatible existing journal/document.
- Dealer onboarding implementation
  - Duplicates: `DealerService.createDealer(...)` vs legacy `SalesService.createDealer(...)`
  - Canonical: `DealerService.createDealer(...)` via `POST /api/v1/dealers`
  - Action: ensure no controller/orchestrator path calls the legacy helper; route onboarding to `DealerService`.
- Orchestrator “status-only truth”
  - Duplicates: orchestrator fulfillment endpoints that can set `SHIPPED/DISPATCHED` vs canonical dispatch confirm
  - Canonical: dispatch confirm (slip→invoice→journals linkage)
  - Action: reject/gate SHIPPED/DISPATCHED updates unless canonical dispatch completed; keep feature-flagged OFF until safe.
- Orchestrator feature flags (defense-in-depth)
  - Duplicates/anti-pattern: prod gating only at controller level can be bypassed by internal service calls.
  - Canonical: feature flags must be enforced in service layer (`CommandDispatcher` / `IntegrationCoordinator`) as well.
  - Action: add service-level guards and tests proving flag-off fails closed even for internal invocation.
- Company context “double source of truth”
  - Duplicates/anti-pattern: orchestrator controllers accept `X-Company-Id` while authenticated company context exists (JWT `cid`)
  - Canonical: authenticated company context (CompanyContextHolder/JWT), not a caller-controlled header
  - Action: derive companyId from auth context or fail-closed (403) on header/JWT mismatch; add tests for header spoofing.

### P1 (High risk / correctness)
- Mutating/nondeterministic slip lookup
  - Duplicates/anti-pattern: `/api/v1/dispatch/order/{orderId}` acts like a “finder” but can create slips/reservations and selects “most recent”
  - Canonical: explicit slipId operations; reservation is a write event, not a GET side-effect
  - Action: make GET read-only and fail-closed on ambiguity; add an admin-only repair endpoint if needed.
  - Status (2026-02-03): ✅ GET now read-only + fails closed on multi-slip; tests: `CR_DispatchOrderLookupReadOnlyIT` (admin-only repair endpoint TBD).
- Payroll entrypoints
  - Duplicates: `/api/v1/hr/payroll-runs` vs `/api/v1/payroll/*` and accounting payroll payments endpoints
  - Canonical: HR payroll run workflow + accounting posting/payment via `AccountingFacade`
  - Action: legacy endpoints become aliases or prod-gated; orchestrator payroll remains disabled until idempotent + safe.
- Manual procurement/stock intake bypasses
  - Duplicates: manual raw material intake vs PO→GRN→Supplier Invoice chain
  - Canonical: procurement chain (GL posts at invoice)
  - Action: keep manual intake prod-gated (admin-only) with explicit idempotency + audit.

### P2 (Surface area + maintainability)
- Dealer and invoice read endpoints
  - Duplicates: dealer invoice list endpoints across staff invoice APIs, dealer controller, and dealer portal controller
  - Canonical: staff uses `/api/v1/invoices/*`, dealer uses `/api/v1/dealer-portal/*`
  - Action: keep all working, but unify filtering/pagination semantics and ensure RBAC/company scoping is consistent.
- Catalog import/list endpoints
  - Duplicates: accounting catalog vs production catalog controllers
  - Canonical: choose one “catalog truth” for staff workflows; keep the other as a read-only alias.

## Acceptance Criteria (For Each Item)
- Alias calls canonical service method (no bypass).
- Retry safety: calling the alias twice returns the same document ids (or fails closed) without duplication.
- Production safety: any unsafe legacy path is prod-gated (feature flag or role-based restriction).
- Tests exist for parity and prod gating (CODE-RED suite names in `docs/CODE-RED/plan-v2.md`).
