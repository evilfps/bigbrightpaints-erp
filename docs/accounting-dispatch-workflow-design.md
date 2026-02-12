# Accounting and Dispatch Workflow Design (Audit Run)

## Purpose
This document captures how critical accounting and dispatch workflows are intended to work, what invariants must hold, and what replay/idempotency behavior is required for safe pre-deployment operation.

Audience:
- Engineering and QA (implementation contracts)
- Product/Ops and documentation teams (plain-language behavior)

## Core Workflow Contracts

### 1) Dispatch is the financial cutover point for sales fulfillment
Entry points:
- `POST /api/v1/sales/dispatch/confirm`
- `POST /api/v1/dispatch/confirm`

Authoritative orchestration:
- `SalesService.confirmDispatch(...)`

Expected sequence:
1. Dispatch quantities are finalized for the packaging slip.
2. COGS and inventory-relief journal is posted from dispatched quantities.
3. AR/Revenue/Tax journal is posted for dispatched value.
4. Invoice is created/linked to the AR journal.
5. Dispatch/invoice/journal linkage is persisted on slip/order.

Must-hold invariants:
- No AR/Revenue posting without dispatch confirmation.
- No COGS posting without dispatch quantity.
- Slip/order/invoice/journal IDs remain linked and replay-safe.
- Duplicate dispatch replay must not double-post journals.

### 2) Backorder cancellation must not auto-promote status without real dispatch
Authoritative logic:
- `FinishedGoodsService.syncOrderStatusAfterBackorderCancellation(...)`

Expected behavior:
- Backorder cancellation can only sync order status to `READY_TO_SHIP` when the active-slip state proves dispatch progress.
- If there is no active `DISPATCHED` slip, status should not be promoted by cancellation logic.

Must-hold invariants:
- No false readiness due to backorder cancellation side effects.
- No status transitions that imply fulfillment before dispatch.

### 3) Dealer receipt (single and split) posting
Entry points:
- `POST /api/v1/accounting/receipts/dealer`
- `POST /api/v1/accounting/receipts/dealer/hybrid`

Expected sequence:
1. Validate dealer/cash accounts and allocation payload.
2. Reserve idempotency mapping (`journal_reference_mappings`) using key + canonical reference.
3. Create journal (cash debits + AR credit).
4. Apply allocations to invoices and sync dealer subledger.

Must-hold invariants:
- Idempotent replay returns the original journal and allocations.
- Replay must relink/repair mapping metadata if mapping exists but is partially unlinked.
- Replay journal source must prefer allocation-linked journal; mapping mismatch with allocations is a conflict.

### 4) Supplier payment posting
Entry point:
- `POST /api/v1/accounting/suppliers/payments`

Expected sequence:
1. Validate supplier/payable/cash accounts and allocation payload.
2. Reserve idempotency mapping.
3. Create journal (AP debit + cash credit).
4. Apply allocations to purchases and sync supplier subledger state.

Must-hold invariants:
- Same replay/mapping rules as dealer receipts.
- Mapping drift under concurrent replay must be repaired or rejected (not silently accepted).

### 5) Dealer and supplier settlements
Entry points:
- `POST /api/v1/accounting/settlements/dealers`
- `POST /api/v1/accounting/settlements/suppliers`

Expected sequence:
1. Validate allocation lines and totals.
2. Build deterministic settlement journal lines.
3. Reserve idempotency mapping and perform exactly-once posting.
4. Persist settlement allocations and ledger effects.

Must-hold invariants:
- Replay must validate partner + allocation signature + journal lines.
- Replay must use allocation-linked journal as source-of-truth.
- If mapping journal and allocation journal disagree, reject as concurrency conflict.

### 6) Period close/reopen and system reversal boundaries
Entry points:
- `POST /api/v1/accounting/periods/{periodId}/close`
- `POST /api/v1/accounting/periods/{periodId}/reopen`

Expected behavior:
- Reopen auto-reversal can use scoped internal override for system-generated reversal posting where needed.
- Manual posting authorization boundaries remain unchanged.

Must-hold invariants:
- Closed/locked period boundaries stay enforced for normal posting.
- Reopen auto-reversal succeeds deterministically for authorized accounting reopen flow.

## Replay and Idempotency Design Rules

### Reservation model
- `reserveReferenceMapping(...)` establishes leader/non-leader behavior per idempotency key.
- Leader path performs creation; non-leader path replays existing artifacts.

### Replay source-of-truth
- When allocations already exist, allocation-linked journal is authoritative for replay.
- Mapping-linked journal is secondary and must match allocation journal when both exist.
- Mismatch must raise conflict; do not relink to an inconsistent journal.

### Mapping repair model
Replay branches must relink mapping fields when safe:
- `canonicalReference`
- `entityId`
- `entityType`

This is required in:
- non-leader replay
- existing-allocation replay
- race fallback (`DataIntegrityViolationException` + concurrent allocation found)

## What Must Never Happen
- Double posting because replay created a new journal.
- Settlement/receipt replay returning a journal that does not match saved allocations.
- Order status implying shippable/shipped without dispatch evidence.
- Period reopen reversal blocked for authorized accounting flow due to internal date-override mismatch.

## Plain-Language Product Behavior (for user docs)
- Dispatch confirmation is the moment financial posting happens for shipped sales.
- Cancelling a backorder does not mean goods were dispatched.
- If the same payment/settlement request is retried with the same idempotency key, the system should return the same accounting outcome, not post again.
- If retry metadata conflicts with already-saved allocations, the system rejects the request instead of guessing.
- Reopening a closed period creates the required reversal safely when the user has the right accounting authority.

## Run Change Ledger (this audit loop)
- `ad3e5988`: fail-closed security defaults.
- `06d2e965`: blank-profile fail-closed + prod hardening stability.
- `11930f7c`: dealer portal boundary hardening + docs smoke checks.
- `1ca9bf23`: period-reopen reversal override hardening.
- `36d56ce0`: supplier settlement replay mapping relink on replay branches.
- Current working slice: replay integrity and mapping repair alignment for dealer receipt / split receipt / supplier payment paths, with allocation-first replay source-of-truth enforcement.

## Update Protocol
- After each commit touching accounting/dispatch invariants, update:
  - this file (intended behavior + invariants)
  - `asyncloop` (timestamped execution ledger)
