# Hydration Update (Block B)

## 2026-02-03
- Scope: Block B (P0) Sales Dispatch‑Truth Safety.
- Changes:
  - Deterministic, read‑only slip lookup for GET /api/v1/dispatch/order/{orderId} (fail closed on 0 or >1 slips).
  - Canonical AR/Revenue references centralized via AccountingFacade with mismatch‑safe idempotency.
  - COGS single‑truth per packing slip; order‑level COGS disabled; dispatch movements link to slip‑scoped journals.
  - Inventory movements now store `packingSlipId` for slip‑scoped linkage.
  - Existing suite failures fixed: Orchestrator idempotency flush + payroll payment creation before PAID.
- Tests:
  - `mvn -B -ntp -Dtest=OrchestratorControllerIT#approve_order_creates_outbox_event test`
  - `mvn -B -ntp -Dtest=ErpInvariantsSuiteIT#hireToPay_goldenPath test`
  - `mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,SalesFulfillmentServiceTest test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed.
