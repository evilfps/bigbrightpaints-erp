# ERP Definition Of Done

Approved mission baseline for the workflow-centric ERP implementation.

> This document defines the **target state for the approved mission scope**. It is not a claim that every milestone below is already implemented.

## Source Of Truth

- Mission proposal: `/home/realnigga/.factory/missions/6e67be87-4261-4db8-a20e-8e8d07b40f17/mission.md`
- Mission boundaries: `/home/realnigga/.factory/missions/6e67be87-4261-4db8-a20e-8e8d07b40f17/AGENTS.md`
- Integration/review base: `origin/Factory-droid`

## Approved Scope

### Phase-one business scope

- **Priority scope:** Order-to-cash (O2C), procure-to-pay (P2P), and accounting/control first.
- **Manufacturing/costing scope:** only far enough to support those O2C/P2P truth boundaries.
- **Out of scope for this mission baseline:** full payroll expansion, deep GST/e-invoice/e-way-bill automation, multi-warehouse redesign, and platform/business crossover work.

### Workflow truth principles

- Business workflow truth drives accounting truth.
- Each touched workflow boundary gets **one canonical posting trigger**.
- No hidden duplicate-truth listeners, silent auto-repair, or ad hoc data-mutation fixes are acceptable.
- Document lifecycle state and accounting lifecycle state must stay separate in touched flows.
- Linked references must remain navigable across source document, fulfillment/receipt, invoice, journal, settlement, return, note, reversal, and approval evidence where applicable.

## Locked Mission Decisions

- **Migration track:** Flyway `migration_v2` only. Do not use, inspect, or depend on any legacy migration track.
- **Costing choice for phase one:** `batch actual + packaging carry-forward`.
- **Settlement granularity for phase one:** invoice or purchase document **header-level only**.
- **Approval model:** admin-only, per-document, mandatory reason.
- **Closed-period exception:** explicit admin approval for a specific document, valid for **one hour only**, then must be requested again after expiry.
- **Delivery style:** quick and working, but production-ready; remove dead/unused/duplicate-truth code in touched O2C/P2P areas as packets land.

## Canonical Truth Boundaries

### O2C

- Dealer onboarding provisions the dealer record, receivable account, and portal identity in the same tenant.
- Credit posture and payment mode are explicit before proforma progression.
- Proforma remains **commercial only**: no stock movement, no journal posting, no receivable posting.
- Factory packaging/dispatch stays operational and must not leak pricing/accounting detail.
- Dispatch confirmation is the canonical trigger for final invoice creation from actual shipped quantities.
- Dispatch must carry logistics metadata and produce a delivery challan artifact.

### P2P

- Supplier onboarding provisions the supplier record, payable account, and explicit lifecycle state.
- Non-usable suppliers remain visible for reference but are transaction-blocked with explicit reasons.
- Goods receipt (GRN) is the **stock truth boundary only**.
- Purchase invoice is the **AP and tax truth boundary only**.
- GRN-to-purchase-invoice linkage must prevent overlapping AP truth, duplicate posting, and drift.

### Accounting And Controls

- Every posted journal must expose provenance to a business workflow document, controlled adjustment, period-close activity, or approved exception path.
- Manual journals are controlled adjustments only, never hidden workflow glue.
- Corrections use linked reversal, return, note, or reissue flows rather than in-place mutation.
- Period close must fail on machine-checkable blockers.

## Canonical Portal Model

- **Admin:** tenant-wide operational + finance visibility, approval/override trail access, per-document exception approval.
- **Accounting:** finance and settlement surfaces, journal provenance, reconciliation, period control views.
- **Sales:** commercial ordering and customer-facing blocker visibility in business language.
- **Factory:** operational production/pack/dispatch truth only, without pricing or accounting detail.
- **Dealer:** read-only access to the dealer's own records only.
- **Super Admin:** platform-only control plane, not tenant business execution.

## Required Execution Order

1. `truth-rails` — freeze current behavior, establish truth rails, and contain duplicate-posting risk.
2. `o2c-truth` — make dispatch the clean commercial-to-accounting truth boundary.
3. `p2p-truth` — make GRN stock truth only and purchase invoice AP truth only.
4. `corrections-and-control` — make corrections, settlements, approvals, and period-close controls explicit.
5. `portal-boundaries` — normalize the tenant role-action matrix and dealer/super-admin boundaries.

## Documentation Deliverables Required By This Mission

- `.factory/library/erp-definition-of-done.md` — canonical scope and truth-boundary reference.
- `.factory/library/remediation-log.md` — packet-level cleanup and duplicate-truth removal record.
- `.factory/library/frontend-v2.md` — backend-facing frontend notes for current and upcoming packet changes.
- `.factory/library/frontend-handoff.md` — detailed endpoint and contract notes as implementation lands.

## Definition Of Done For This Docs Baseline Packet

This baseline packet is done when:

- the approved workflow-centric ERP scope is recorded accurately,
- the canonical portal model and execution order are explicit,
- the Flyway v2-only rule is unambiguous,
- the phase-one costing, settlement, and approval decisions match the mission record exactly,
- and the document does **not** overstate unimplemented milestones as already delivered.
