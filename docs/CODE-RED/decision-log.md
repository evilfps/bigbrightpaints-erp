# Decision Log (CODE-RED)

## 2026-01-27 - Dispatch-Truth Invoicing + Posting
Decision:
- Canonical path for shipping/invoicing/posting is `SalesService.confirmDispatch(...)`.
- Invoice quantity + AR/Revenue/Tax + COGS are derived from shipped quantities (dispatch truth).

Rationale:
- Eliminates order-truth revenue recognition and double-post risks.
- Aligns postings with actual stock movements and packaging slip idempotency.

Enforcement:
- Order-truth posting paths are disabled or fail closed.
- Slipless invoice issuance fails closed unless an invoice already exists.

## 2026-01-27 - Payroll Single Canonical Path
Decision:
- Canonical payroll computation lives in HR service layer; posting is owned by Accounting via `AccountingFacade`.
- Alternate/legacy payroll creation/posting paths are routed or disabled.

Rationale:
- Prevents duplicate payroll runs and inconsistent idempotency/posting logic.

Enforcement:
- Payroll run idempotency is enforced by scope (company + runType + period).

## 2026-01-27 - Manual Journal Reference Namespace
Decision:
- Manual journal idempotency keys must not use system-reserved reference prefixes.
- Manual journal idempotency keys should use the `MANUAL-` prefix (recommended) and must include a memo/reason.

Rationale:
- Prevents collisions with system-generated references (SALE-/INV-/COGS-/RMP-/etc).
- Improves auditability and replay/idempotency safety.

Enforcement:
- Reserved prefix list is centralized and validated on manual journal creation (idempotency key only).

## 2026-01-28 - Manual Journal References Are System-Generated Only
Decision:
- Manual journal entry API treats caller-supplied `referenceNumber` as an idempotency key only.
- Canonical reference numbers for manual journals are always system-generated.

Rationale:
- Prevents any collision with system reference namespaces (including company-prefixed invoice numbers).
- Eliminates audit/log integrity risks from user-selected references.

Enforcement:
- Manual journal creation fails closed if the idempotency key matches a system-reserved namespace
  (including company-prefixed invoice numbers like `*-INV-*`).

## 2026-01-27 - CompanyClock Is Canonical For Business Dates
Decision:
- Business date and timezone handling uses `CompanyClock` everywhere (company timezone), never server timezone.

Rationale:
- Prevents month-boundary and period-close errors caused by server timezone drift.

Enforcement:
- ZoneId.systemDefault() is forbidden in business logic (gate via review + scan).

## 2026-01-28 - Manufacturing & Packaging Canonical Flow (Bulk -> Size SKUs)
Decision:
- Bulk batches (SKU-BULK) are the only source for size SKUs.
- Packing is batch-based and uses per-product variants + BOM (no legacy size-only mappings).
- Packing is deterministic, idempotent, and posts conversion journals via AccountingFacade.

Rationale:
- Removes ambiguous bulk/size mapping and ensures traceable, auditable cost flow.
- Aligns inventory and accounting with real factory operations.

Enforcement:
- Hard cutover to the canonical flow defined in `docs/CODE-RED/packaging-flow.md`.
- Packing fails closed if any variant or BOM is missing.
