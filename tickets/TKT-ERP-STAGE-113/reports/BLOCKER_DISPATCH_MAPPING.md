# TKT-ERP-STAGE-113 Blocker Dispatch Mapping

Date: 2026-02-26  
Ticket: `TKT-ERP-STAGE-113`  
Mode: planning-only addendum (no implementation/merge/review/QA sign-off)

## Problem Framing And Assumptions

Blocker remediation already defines blocker-level branches/worktrees in the slice matrix, but execution handoff can stall when the ticket-level slices (`SLICE-01..SLICE-10`) are not explicitly mapped to blocker-specific branches and wave order. This addendum resolves dispatch ambiguity by binding each blocker to a parent slice owner plus explicit branch/worktree coordinates.

Assumptions:
- Parent slice metadata is sourced from `tickets/TKT-ERP-STAGE-113/ticket.yaml`.
- Blocker branch/worktree coordinates are sourced from `tickets/TKT-ERP-STAGE-113/reports/BLOCKER_SLICE_MATRIX.md`.
- Wave ordering is sourced from `tickets/TKT-ERP-STAGE-113/reports/BLOCKER_EXECUTION_PLAN.md`.
- Non-doc blocker flow remains mandatory: `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`.

## Scope Boundaries And Non-Goals

In scope:
- Slice-to-blocker dispatch mapping.
- Blocker execution order with dependencies.
- Ownership clarity for implementation/merge/review/QA/release per blocker.

Out of scope:
- Code changes.
- Branch merges.
- Final code-review or QA approvals.

## Slice To Blocker Ownership Mapping

| parent_slice_id | parent_primary_agent | parent_slice_branch | parent_slice_worktree_path | mapped_blockers |
| --- | --- | --- | --- | --- |
| SLICE-02 | auth-rbac-company | `tickets/tkt-erp-stage-113/auth-rbac-company` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/auth-rbac-company` | `B01,B08,B10` |
| SLICE-03 | hr-domain | `tickets/tkt-erp-stage-113/hr-domain` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/hr-domain` | `B05` |
| SLICE-04 | orchestrator-runtime | `tickets/tkt-erp-stage-113/orchestrator-runtime` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator-runtime` | `B02,B09` |
| SLICE-05 | inventory-domain | `tickets/tkt-erp-stage-113/inventory-domain` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/inventory-domain` | `B04,B13` |
| SLICE-06 | orchestrator | `tickets/tkt-erp-stage-113/orchestrator` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator` | `B07,B14` |
| SLICE-07 | purchasing-invoice-p2p | `tickets/tkt-erp-stage-113/purchasing-invoice-p2p` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/purchasing-invoice-p2p` | `B06` |
| SLICE-08 | reports-admin-portal | `tickets/tkt-erp-stage-113/reports-admin-portal` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/reports-admin-portal` | `B12` |
| SLICE-09 | sales-domain | `tickets/tkt-erp-stage-113/sales-domain` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/sales-domain` | `B03` |
| external-factory-production | factory-production (external upstream/downstream agent) | `n/a (external)` | `n/a (external)` | `B11` |

## Blocker Dispatch Queue (Execution Order + Branch/Worktree)

| execution_order | wave | blocker_id | mapped_slice_id | implementation_role | merge_role | review_role | qa_role | release_role | blocker_branch | blocker_worktree_path | depends_on |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | W0 | B07 | SLICE-06 | orchestrator | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b07-governance-traceability` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B07-governance-traceability` | none |
| 2 | W0 | B01 | SLICE-02 | auth-rbac-company | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b01-auth-tenant-isolation` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B01-auth-tenant-isolation` | B07 |
| 3 | W0 | B08 | SLICE-02 | auth-rbac-company | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b08-auth-secret-hardening` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B08-auth-secret-hardening` | B01 |
| 4 | W0 | B02 | SLICE-04 | orchestrator-runtime | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b02-orchestrator-outbox-atomicity` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B02-orchestrator-outbox-atomicity` | B07 |
| 5 | W0 | B09 | SLICE-04 | orchestrator-runtime | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b09-orchestrator-correlation-sanitization` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B09-orchestrator-correlation-sanitization` | B02 |
| 6 | W0 | B14 | SLICE-06 | orchestrator | merge-specialist | code_reviewer | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b14-verifylocal-bash32-portability` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B14-verifylocal-bash32-portability` | B07 |
| 7 | W1 | B03 | SLICE-09 | sales-domain | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b03-sales-failclosed-return` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B03-sales-failclosed-return` | B01,B02 |
| 8 | W1 | B04 | SLICE-05 | inventory-domain | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b04-inventory-dispatch-eligibility` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B04-inventory-dispatch-eligibility` | B03 |
| 9 | W1 | B13 | SLICE-05 | inventory-domain | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b13-inventory-adjustment-reversal` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B13-inventory-adjustment-reversal` | B04 |
| 10 | W1 | B05 | SLICE-03 | hr-domain | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b05-payroll-reversal-continuity` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B05-payroll-reversal-continuity` | B01 |
| 11 | W1 | B06 | SLICE-07 | purchasing-invoice-p2p | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b06-p2p-return-void-reopen` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B06-p2p-return-void-reopen` | B01,B04 |
| 12 | W2 | B10 | SLICE-02 | auth-rbac-company | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b10-auth-recovery-continuity` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B10-auth-recovery-continuity` | B01,B08 |
| 13 | W2 | B11 | external-factory-production | factory-production | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b11-factory-lifecycle-recovery` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B11-factory-lifecycle-recovery` | B01,B04 |
| 14 | W2 | B12 | SLICE-08 | reports-admin-portal | merge-specialist | code_reviewer + security-governance | qa-reliability | release-ops | `tickets/tkt-erp-stage-113/b12-reports-export-denial-envelope` | `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B12-reports-export-denial-envelope` | B01,B09 |

## Acceptance Criteria For This Dispatch Mapping Addendum

1. Every blocker `B01-B14` is mapped to exactly one parent slice (or explicit external owner) and one blocker branch/worktree.
2. Execution order is dependency-valid relative to the blocker execution plan waves.
3. Every blocker row names implementation, merge, review, QA, and release roles.
4. Mapping is traceable to existing ticket artifacts (`ticket.yaml`, blocker matrix, execution plan).

## Verification Strategy And Evidence

- Structural check: blocker count and uniqueness.
  - `rg -n "\| [0-9]+ \| W[0-9] \| B[0-9]{2} \|" tickets/TKT-ERP-STAGE-113/reports/BLOCKER_DISPATCH_MAPPING.md`
- Dependency check: ensure each `depends_on` references earlier queue rows.
  - Manual review against queue ordering in this file and dependencies section in `BLOCKER_SLICE_MATRIX.md`.
- Artifact consistency check:
  - Compare branch/worktree values with `BLOCKER_SLICE_MATRIX.md` entries.

## Risk Register And Rollback Strategy

| risk_id | trigger | impact | mitigation owner | rollback strategy |
| --- | --- | --- | --- | --- |
| DM-01 | Branch/worktree names drift from matrix updates | Dispatch scripts target wrong worktree | orchestrator | Regenerate this addendum from latest matrix and rerun structural checks |
| DM-02 | External owner (`B11`) availability lag | Wave-2 blocker queue stalls | orchestrator + release-ops | Re-sequence within wave after documenting dependency impact and preserving blockers already merged |
| DM-03 | Dependency violation during manual dispatch | Cross-module regressions and merge conflicts | merge-specialist + qa-reliability | Stop queue, revert unmerged dispatch assignment, resume from last validated blocker |

## Cross-Module Impact Map (Dispatch Perspective)

| blocker_group | upstream contract pressure | downstream consumer pressure |
| --- | --- | --- |
| Auth and orchestrator foundation (`B01,B02,B08,B09,B10`) | tenant isolation, idempotency, security policy | sales, inventory, reports, payroll, factory owners consume these contracts |
| Lifecycle continuity (`B03,B04,B05,B06,B11,B13`) | order/dispatch/payroll/p2p/factory state-machine invariants | accounting reconciliation, inventory valuation, operator workflows |
| Governance and release reliability (`B07,B12,B14`) | claim/timeline integrity, denial envelope consistency, local gate reproducibility | merge-readiness confidence, support recovery workflows, release evidence quality |
