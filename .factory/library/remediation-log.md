# Remediation Log

Track cleanup, duplicate-truth removals, dead-code retirement, and production-readiness fixes for this mission.

## Logging Rules

- Add entries in chronological order.
- Log packet-level cleanup only when backed by merged code or reviewable evidence.
- Describe what was cleaned, why it was risky, and what proof shows the cleanup is real.
- Do **not** describe planned cleanup as already completed.

## Entry Template

### YYYY-MM-DD — `<feature-id>`

- **Area:**
- **Risk addressed:**
- **Cleanup/remediation performed:**
- **Duplicate-truth or dead-code impact:**
- **Evidence:**
- **Follow-up:**

## 2026-03-08 — `truth-rails.docs-baseline`

- **Area:** mission documentation baseline
- **Risk addressed:** future packets needed a single approved reference for scope, truth boundaries, frontend notes, and cleanup logging; without that baseline, later workers could drift or overstate unimplemented behavior.
- **Cleanup/remediation performed:** initialized the mission remediation scaffold, definition-of-done reference, and frontend-v2 working notes.
- **Duplicate-truth or dead-code impact:** no application cleanup was performed in this docs-only packet; this entry creates the structure future packets must use when they remove duplicate-truth or dead code.
- **Evidence:** `.factory/library/erp-definition-of-done.md`, `.factory/library/frontend-v2.md`, and this file were aligned to the approved mission scope and Flyway v2-only rule.
- **Follow-up:** future truth-rails, O2C, P2P, control, and portal packets must append concrete remediation entries as code cleanup lands.

## 2026-03-08 — `truth-rails.freeze-characterization-contracts`

- **Area:** O2C/P2P/control truth-boundary characterization coverage
- **Risk addressed:** the current packet needed executable proof around replay and linkage seams before deeper refactors, especially where duplicate-truth could be introduced by dispatch re-entry, GRN/purchase invoice crossover, return replay, settlement reference reuse, or inventory side-listeners.
- **Cleanup/remediation performed:** no production code was changed; this packet added characterization tests that freeze current behavior for invoice replay without redispatch, stock-only GRN posting, purchase-invoice journal linkage to receipt movements, replay-safe sales returns, settlement reference reuse fail-closed behavior, and listener skip behavior when inventory events lack accounting accounts.
- **Duplicate-truth or dead-code impact:** no duplicate-truth path was removed yet, but the new tests document that `InventoryAccountingEventListener` remains a side-channel risk to contain later, `InvoiceService` still depends on dispatch reconciliation for issuance, and settlement reference reuse currently fails closed instead of silently creating duplicate allocations.
- **Evidence:** `InvoiceServiceTest`, `SalesReturnServiceTest`, `PurchasingServiceGoodsReceiptTest`, `PurchaseInvoiceEngineLifecycleTest`, `InventoryAccountingEventListenerIT`, and `CR_DealerReceiptSettlementAuditTrailTest` now pin these seams with targeted assertions.
- **Follow-up:** use these tests as guardrails while splitting canonical workflow-versus-accounting truth and while containing listener-driven posting in subsequent truth-rails packets.

## Known Cleanup Watchlist For Upcoming Packets

- `SalesCoreEngine` — mixed workflow/accounting decisions make O2C truth boundaries hard to reason about.
- `InvoiceService` — invoice issuance still has dispatch-coupled risk to untangle.
- `GoodsReceiptService` — must stay stock-truth only and replay-safe.
- `PurchaseInvoiceEngine` — needs clean GRN linkage without overlapping AP truth.
- `InventoryAccountingEventListener` — duplicate-posting and side-channel accounting risk must be contained early.

## 2026-03-08 — `o2c-truth.dealer-credit-and-proforma-boundary`

- **Area:** dealer onboarding plus O2C commercial boundary handling in `DealerService` and `SalesCoreEngine`
- **Risk addressed:** proforma creation was still reserving stock immediately, cash orders were treated like credit exposure, and shortage handling created one-off factory tasks without a clean refresh path.
- **Cleanup/remediation performed:** kept dealer onboarding on the tenant-safe `DealerService` provisioning flow, made credit posture use outstanding plus pending order exposure for credit-backed proformas, normalized payment modes to explicit `CREDIT`/`CASH`/`HYBRID`, removed in-create/update reservation side effects, and replaced duplicate shortage task creation with refreshable production-requirement syncing.
- **Duplicate-truth or dead-code impact:** retired the old create/update shortage branch that reserved inventory during commercial proforma edits and removed the separate pending-task cleanup path that no longer matched the new requirement-sync behavior.
- **Evidence:** `DealerServiceTest`, `SalesServiceTest`, `ErpInvariantsSuiteIT`, and the broader `mvn -T8 test -Pgate-fast -Djacoco.skip=true` gate all pass with the new commercial-only proforma flow.
- **Follow-up:** dispatch/final-invoicing packet should keep using dispatch-time reservation/slip creation as the fulfillment boundary while preserving the new payment-mode field and production-requirement semantics.
