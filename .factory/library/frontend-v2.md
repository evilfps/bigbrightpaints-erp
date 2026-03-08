# Frontend V2 Notes

Backend-facing working notes for frontend-v2 consumers.

## Baseline Status

- Initialized: `2026-03-08`
- Detailed contract reference: `.factory/library/frontend-handoff.md`
- Mission source of truth: `.factory/library/erp-definition-of-done.md`

> This file is a mission baseline and tracking surface. It does **not** mean the O2C/P2P/control/portal changes below are already shipped.

## Current Frontend Guidance

- This repository is backend-first; there is no separate frontend application in-repo for this mission.
- This docs packet does **not** ship new API behavior by itself.
- Until implementation packets merge, frontend should treat the milestone notes below as **planned backend contract direction**, not delivered runtime behavior.
- For already-implemented endpoint details outside this mission baseline, continue to use `.factory/library/frontend-handoff.md`.

## Approved Direction The Frontend Should Track

### Scope order

1. `truth-rails`
2. `o2c-truth`
3. `p2p-truth`
4. `corrections-and-control`
5. `portal-boundaries`

### Backend contract themes expected from this mission

- O2C and P2P flows will move toward single canonical truth boundaries.
- Workflow state and accounting state will be exposed separately where this mission touches documents.
- Dealer and supplier settlements in phase one are header-level only.
- Admin-only approvals and one-hour closed-period exceptions will become explicit contract concepts.
- Factory-facing surfaces must avoid pricing/accounting leakage.
- Dealer surfaces remain read-only and limited to own-record visibility.

## Working Notes By Milestone

### truth-rails

- Establishes the documentation baseline and the guardrails for truth-boundary refactors.
- Watch for future packet notes on replay contracts, linked references, provenance, and duplicate-truth containment.

### o2c-truth

- Expected frontend-sensitive areas: dealer onboarding, payment-mode capture, dispatch logistics metadata, delivery challan access, and role-filtered factory responses.
- Do not assume current proforma/dispatch/invoice payloads have changed until those packets land and are documented in `.factory/library/frontend-handoff.md`.

#### 2026-03-08 implemented note — `o2c-truth.phase-one-cost-carry-forward`

- Profitability-facing report surfaces now follow the reserved/dispatched finished-good batch cost that already includes production actuals plus packaging carry-forward; they no longer drift to a company-wide weighted-average cost when the dispatch path already knows the exact batch.
- Inventory valuation low-stock reporting now mirrors stock-summary semantics for reserved-over-on-hand finished goods, so frontend consumers may see low-stock counts where reserved demand exceeds current stock instead of that drift being masked.
- No endpoint shapes changed in this packet; the update is behavioral/semantic only.

### p2p-truth

- Expected frontend-sensitive areas: supplier lifecycle blockers, GRN vs purchase-invoice boundary, and supplier-settlement guidance.
- Any blocker-message or state-field changes must be recorded here and in `.factory/library/frontend-handoff.md` when implemented.

### corrections-and-control

- Expected frontend-sensitive areas: explicit correction outcomes, settlement override flows, admin approval prompts, and period-close blocker reporting.

### portal-boundaries

- Expected frontend-sensitive areas: role-action matrix, dealer read-only behavior, super-admin isolation, and role-appropriate blocker language.

## Update Rules For Future Packets

- Record only backend-facing facts supported by merged code, tests, or approved mission evidence.
- Label planned but unmerged behavior as pending/future work.
- Add endpoint, role-surface, blocker, artifact, and migration notes here when a packet changes frontend expectations.
