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
- Manual journals must not use system-reserved reference prefixes.
- Manual journals should use the `MANUAL-` prefix (recommended) and must include a memo/reason.

Rationale:
- Prevents collisions with system-generated references (SALE-/INV-/COGS-/RMP-/etc).
- Improves auditability and replay/idempotency safety.

Enforcement:
- Reserved prefix list is centralized and validated on manual journal creation.

## 2026-01-27 - CompanyClock Is Canonical For Business Dates
Decision:
- Business date and timezone handling uses `CompanyClock` everywhere (company timezone), never server timezone.

Rationale:
- Prevents month-boundary and period-close errors caused by server timezone drift.

Enforcement:
- ZoneId.systemDefault() is forbidden in business logic (gate via review + scan).

